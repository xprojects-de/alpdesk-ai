package de.alpdesk.services.sandbox

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micronaut.json.JsonMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's side of the sandbox protocol (docs/architecture/code-execution.md) against instances that answer like the
 * real one (Backend/podman/sandbox/app.py): a tenant's instances with its token, one session per instance, a free
 * one leased per answer, released when it ends; the admin's check of an instance.
 */
@MicronautTest
class SandboxesSpec extends Specification {

    static final String TOKEN = "t" * 40
    /** An instance that does not answer. */
    static final String DOWN = "down"
    static final String README = "simtest runs the company's test scripts: simtest run <file>"

    @Inject
    JsonMapper json

    List<Instance> instances = []
    Sandboxes sandboxes

    def setup() {
        sandboxes = new Sandboxes(Duration.ofSeconds(5), Duration.ofSeconds(1), json)
    }

    def cleanup() {
        instances*.server*.stop(0)
    }

    def "no instance entered: no code execution"() {
        expect:
        !sandboxes.available([])
        sandboxes.open([], TOKEN, []).isEmpty()
    }

    def "a tenant's addresses are read from what is stored: #stored"() {
        expect:
        Sandboxes.addresses(stored) == expected

        where:
        stored                                      | expected
        null                                        | []
        ""                                          | []
        "http://a:8080"                             | ["http://a:8080"]
        " http://a:8080/ , ,http://b:8080//"        | ["http://a:8080", "http://b:8080"]
    }

    def "a session gets its files, runs Python and the shell with the token, and frees its instance when it ends"() {
        given:
        def instance = start()

        when:
        def session = sandboxes.open([instance.url], TOKEN,
                [SandboxFile.of("a.txt", "Hallo".getBytes(StandardCharsets.UTF_8))]).get()
        def python = session.python("1 + 1")
        def shell = session.shell("ls data")

        then:
        sandboxes.available([instance.url])
        instance.files == ["a.txt": "Hallo"]
        instance.tokens.every { it == "Bearer " + TOKEN }
        python.output() == "ran 1 + 1" && python.error() == null
        shell.output() == "ran ls data" && shell.exitCode() == 0

        when: "the answer ends"
        session.close()
        session.close()

        then: "the instance is told once, and serves the next answer"
        instance.deleted == 1
        sandboxes.open([instance.url], TOKEN, []).isPresent()
    }

    def "the instances are spoken to over HTTP/1.1 - an upgrade request would be logged by the sandbox on every call"() {
        given:
        def instance = start()

        when:
        def session = sandboxes.open([instance.url], TOKEN, []).get()
        session.python("1 + 1")
        session.close()

        then:
        instance.upgrades.size() >= 3
        instance.upgrades.every { it == null }
    }

    def "every tenant's session goes to its own instances with its own token"() {
        given:
        def first = start(token: "a" * 40)
        def second = start(token: "b" * 40)

        when:
        def firstSession = sandboxes.open([first.url], "a" * 40, []).get()
        def secondSession = sandboxes.open([second.url], "b" * 40, []).get()
        firstSession.python("x")
        secondSession.shell("y")
        firstSession.close()
        secondSession.close()

        then:
        first.tokens.every { it == "Bearer " + "a" * 40 } && first.tokens.size() == 3
        second.tokens.every { it == "Bearer " + "b" * 40 } && second.tokens.size() == 3
    }

    def "an instance entered at two tenants by mistake is still never leased twice at once"() {
        given:
        def instance = start()

        when:
        def first = sandboxes.open([instance.url], TOKEN, [])
        def second = sandboxes.open([instance.url], TOKEN, [])

        then:
        first.isPresent()
        second.isEmpty()
    }

    def "the admin's check of an instance: #expected"() {
        given:
        def instance = start(busy: busy, unusable: unusable)
        def url = down ? "http://127.0.0.1:1" : instance.url

        expect:
        sandboxes.check(url, token) == expected
        instance.deleted == (expected == Sandboxes.Status.REACHABLE ? 1 : 0)

        where:
        busy  | down  | unusable                            | token    | expected
        false | false | null                                | TOKEN    | Sandboxes.Status.REACHABLE
        true  | false | null                                | TOKEN    | Sandboxes.Status.BUSY
        false | false | null                                | "x" * 40 | Sandboxes.Status.TOKEN_REFUSED
        true  | false | null                                | "x" * 40 | Sandboxes.Status.TOKEN_REFUSED
        false | true  | null                                | TOKEN    | Sandboxes.Status.UNREACHABLE
        true  | false | "this container may not send signals" | TOKEN    | Sandboxes.Status.UNUSABLE
    }

    def "an instance that takes no session until it is started again is no code execution - the tools are not offered"() {
        given:
        def instance = start(busy: true, unusable: "the processes of a session could not be killed")

        expect:
        !sandboxes.available([instance.url])
        sandboxes.open([instance.url], TOKEN, []).isEmpty()
        instance.tokens.isEmpty()
    }

    def "the check finds an instance busy while an answer of this app holds it, without touching its session"() {
        given:
        def instance = start()
        def session = sandboxes.open([instance.url], TOKEN, []).get()

        expect:
        sandboxes.check(instance.url, TOKEN) == Sandboxes.Status.BUSY
        instance.deleted == 0

        cleanup:
        session.close()
    }

    def "a busy instance is passed over for a free one - without getting the files"() {
        given:
        def busy = start(busy: true)
        def free = start()

        expect:
        sandboxes.open([busy.url, free.url], TOKEN, []).get().python("x").output() == "ran x"
        !free.tokens.isEmpty()
        busy.tokens.isEmpty()
    }

    def "a refused token ends the wait at once and says so"() {
        given:
        def instance = start()

        when:
        sandboxes.open([instance.url], "x" * 40, [])

        then:
        thrown(Sandboxes.TokenRefused)
        instance.tokens.size() == 1

        and: "the instance was not kept leased - the right token gets it at once"
        sandboxes.open([instance.url], TOKEN, []).isPresent()
    }

    def "files the instance refuses end the wait at once - another try would be refused the same way"() {
        given:
        def instance = start(refusesFiles: true)

        when:
        sandboxes.open([instance.url], TOKEN, [SandboxFile.of("a.txt", "Hallo".getBytes(StandardCharsets.UTF_8))])

        then: "the session opened for them is ended again"
        thrown(Sandboxes.FilesRefused)
        instance.deleted == 1

        when: "the instance was not kept leased - smaller files get it at once"
        instance.refusesFiles = false

        then:
        sandboxes.open([instance.url], TOKEN, []).isPresent()
    }

    def "every file is streamed into the session on its own, with its folders, its length and the token"() {
        given:
        def instance = start()
        byte[] video = new byte[3 * 1024 * 1024]
        new Random(1).nextBytes(video)

        when:
        def session = sandboxes.open([instance.url], TOKEN, [
                new SandboxFile("clips/lauf 1.mp4", video.length, { new ByteArrayInputStream(video) }),
                SandboxFile.of("leer.txt", new byte[0])]).get()

        then:
        instance.received["clips/lauf 1.mp4"] == video
        instance.received["leer.txt"] == new byte[0]
        instance.lengths == ["clips/lauf 1.mp4": video.length as String, "leer.txt": "0"]
        instance.tokens.every { it == "Bearer " + TOKEN }

        cleanup:
        session?.close()
    }

    def "a file the storage cannot read refuses the files - the instance is not taken for unreachable"() {
        given:
        def instance = start()

        when:
        sandboxes.open([instance.url], TOKEN, [new SandboxFile("weg.mp4", 10, { throw new IOException("missing") })])

        then:
        thrown(Sandboxes.FilesRefused)
        instance.deleted == 1
        sandboxes.available([instance.url])
        sandboxes.open([instance.url], TOKEN, []).isPresent()
    }

    def "a file the code wrote is read back; a missing or too large one is a text for the model"() {
        given:
        def instance = start()
        instance.written["out/frame_001.jpg"] = [1, 2, 3, 4] as byte[]
        def session = sandboxes.open([instance.url], TOKEN, []).get()

        expect:
        session.file("out/frame_001.jpg", 10).content() == [1, 2, 3, 4] as byte[]
        session.file("out/frame_001.jpg", 10).error() == null
        session.file("out/fehlt.jpg", 10).error().contains("there is no file \"out/fehlt.jpg\"")
        session.file("out/frame_001.jpg", 3).error().contains("at most")
        session.file("out/frame_001.jpg", 3).content() == null
        instance.paths == ["out/frame_001.jpg", "out/frame_001.jpg", "out/fehlt.jpg", "out/frame_001.jpg",
                           "out/frame_001.jpg"]

        cleanup:
        session.close()
    }

    def "reading a file of a session the instance ended by itself tells the model, and frees the instance"() {
        given:
        def instance = start()
        def session = sandboxes.open([instance.url], TOKEN, []).get()
        instance.endedSession = true

        when:
        def file = session.file("out/a.jpg", 10)

        then:
        file.error().contains("the session ended")
        session.isGone()
        sandboxes.open([instance.url], TOKEN, []).isPresent()
    }

    def "whether a tenant's instances examine videos, as their /health says: #reason"() {
        given:
        def urls = videos.collect { video -> video == null ? "http://127.0.0.1:1" : start(video: video).url }

        expect:
        sandboxes.video(urls) == expected
        urls.collect { sandboxes.video(it) } == videos

        where:
        reason                                              | videos        || expected
        "the video image"                                   | [true]        || Sandboxes.Video.YES
        "the sandbox without video"                         | [false]       || Sandboxes.Video.NO
        "one of two is not - a session may land on it"      | [true, false] || Sandboxes.Video.NO
        "the one answering is, the other does not answer"   | [true, null]  || Sandboxes.Video.YES
        "none answers"                                      | [null]        || Sandboxes.Video.UNKNOWN
    }

    def "the README of an image built on ours is kept with the health - the model gets it only when all agree: #reason"() {
        given:
        def urls = readmes.collect { readme -> readme == DOWN ? "http://127.0.0.1:1" : start(extras: readme).url }

        expect:
        sandboxes.extras(urls) == expected
        urls.collect { sandboxes.extras(it) } == flags
        sandboxes.extrasAgree(urls) == agree

        where:
        reason                                                  | readmes                   || expected | flags         | agree
        "ours, without one"                                     | [null]                    || null     | [false]       | true
        "one instance with one"                                 | [README]                  || README   | [true]        | true
        "two with the same"                                     | [README, README]          || README   | [true, true]  | true
        "two with different ones"                               | [README, "other"]         || null     | [true, true]  | false
        "one without among one with - a session may land on it" | [README, null]            || null     | [true, false] | false
        "the one answering has it, the other is down"           | [README, DOWN]            || README   | [true, null]  | true
        "none answers"                                          | [DOWN]                    || null     | [null]        | true
    }

    def "a session the instance ended by itself is gone - its instance is free again, the model is told"() {
        given:
        def instance = start()
        def session = sandboxes.open([instance.url], TOKEN, []).get()
        instance.endedSession = true

        when:
        def python = session.python("x")
        def shell = session.shell("y")

        then:
        python.error().contains("the session ended") && python.output() == ""
        shell.exitCode() == null && shell.output().contains("the session ended")
        session.isGone()

        and: "the lease is released, nothing is marked unreachable"
        sandboxes.available([instance.url])
        sandboxes.open([instance.url], TOKEN, []).isPresent()
    }

    def "an instance that does not answer is skipped, and its failure mid-session is a text for the model"() {
        given:
        def instance = start()

        when:
        def session = sandboxes.open(["http://127.0.0.1:1", instance.url], TOKEN, []).get()
        instance.server.stop(0)
        def result = session.python("x")

        then:
        result.error().contains("not reachable")

        and: "the instances count as down, so the Toolbox answers the answer's next calls at once"
        !sandboxes.available(["http://127.0.0.1:1", instance.url])
    }

    private Instance start(Map options = [:]) {
        def instance = new Instance(busy: options.busy ?: false, token: options.token ?: TOKEN,
                refusesFiles: options.refusesFiles ?: false, video: options.video ?: false, extras: options.extras, unusable: options.unusable)
        instance.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        instance.server.createContext("/") { HttpExchange exchange -> instance.handle(exchange, json) }
        instance.server.start()
        instance.url = "http://127.0.0.1:" + instance.server.address.port
        instances << instance
        instance
    }

    /**
     * Answers like Backend/podman/sandbox/app.py: health without a token, everything else 401 without its token,
     * sessions (409 when busy), files in and out, python, shell, delete.
     */
    static class Instance {
        HttpServer server
        String url
        String token
        boolean busy
        /** The video image: its /health says so. */
        boolean video
        /** The README of an image built on ours - null for ours. */
        String extras
        /** Why the instance takes no session until it is started again (busy too) - null while it does. */
        String unusable
        /** Refuses every file streamed in, like the real one a file over SANDBOX_FILE_MB (413). */
        boolean refusesFiles
        /** The instance ended the session itself (idle or lifetime): its calls answer 404. */
        boolean endedSession
        /** The files streamed in, as text - and as they came, with the length each request declared. */
        Map<String, String> files = [:]
        Map<String, byte[]> received = [:]
        Map<String, String> lengths = [:]
        /** The files the session's code "wrote", by path - and every path asked for. */
        Map<String, byte[]> written = [:]
        List<String> paths = new CopyOnWriteArrayList<>()
        int deleted = 0
        List<String> tokens = new CopyOnWriteArrayList<>()
        /** The Upgrade header of every request - Java's client asks for h2c on plain HTTP unless told HTTP/1.1. */
        List<String> upgrades = new CopyOnWriteArrayList<>()

        void handle(HttpExchange exchange, JsonMapper json) {
            String path = exchange.requestURI.path
            upgrades << exchange.requestHeaders.getFirst("Upgrade")
            if (path == "/health") {
                reply(exchange, 200, [ok: true, busy: busy, video: video, extras: extras, unusable: unusable], json)
                return
            }
            String given = exchange.requestHeaders.getFirst("Authorization")
            tokens << given
            if (given != "Bearer " + token) {
                reply(exchange, 401, [error: "unauthorized"], json)
                return
            }
            byte[] request = exchange.requestBody.bytes
            if (path.endsWith("/files")) {
                files(exchange, request, json)
                return
            }
            Map body = request ? json.readValue(request, Map) : [:]
            if (path == "/sessions") {
                if (busy) {
                    reply(exchange, 409, [error: "busy"], json)
                    return
                }
                reply(exchange, 201, [session: "a" * 32], json)
            } else if (endedSession) {
                reply(exchange, 404, [error: "no such session"], json)
            } else if (path.endsWith("/python")) {
                reply(exchange, 200, [output: "ran " + body.code, error: null], json)
            } else if (path.endsWith("/shell")) {
                reply(exchange, 200, [output: "ran " + body.command, exitCode: 0], json)
            } else if (exchange.requestMethod == "DELETE") {
                deleted++
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
        }

        /** PUT: a file into data/ (413 when it refuses files); GET: a file the code wrote, 400 for none. */
        private void files(HttpExchange exchange, byte[] request, JsonMapper json) {
            if (endedSession) {
                reply(exchange, 404, [error: "no such session"], json)
                return
            }
            String query = URLDecoder.decode(exchange.requestURI.rawQuery, StandardCharsets.UTF_8)
            if (exchange.requestMethod == "PUT") {
                if (refusesFiles) {
                    reply(exchange, 413, [error: "request too large"], json)
                    return
                }
                String name = query.substring("name=".length())
                received[name] = request
                files[name] = new String(request, StandardCharsets.UTF_8)
                lengths[name] = exchange.requestHeaders.getFirst("Content-Length")
                reply(exchange, 201, [name: name], json)
                return
            }
            String asked = query.substring("path=".length())
            paths << asked
            if (!written.containsKey(asked)) {
                reply(exchange, 400, [error: "no such file in the session"], json)
                return
            }
            exchange.sendResponseHeaders(200, written[asked].length)
            exchange.responseBody.withCloseable { it.write(written[asked]) }
        }

        private static void reply(HttpExchange exchange, int status, Map body, JsonMapper json) {
            byte[] data = json.writeValueAsBytes(body)
            exchange.sendResponseHeaders(status, data.length)
            exchange.responseBody.withCloseable { it.write(data) }
        }
    }
}
