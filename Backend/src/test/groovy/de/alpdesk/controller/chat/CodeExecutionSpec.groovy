package de.alpdesk.controller.chat

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.sandbox.FakeSandboxes
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Python and shell in the chat (docs/architecture/code-execution.md): offered only when the admin entered the tenant's
 * own sandbox and it answers; one session per answer on the tenant's instances with the question's files, closed when
 * the answer ends - however it ends. The model looks at images its code wrote (view_images) and hands files of the
 * session to the user (hand_out_file).
 */
@MicronautTest(transactional = false)
class CodeExecutionSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    FakeSandboxes sandboxes

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
        sandboxes.reset()
    }

    static final String TOKEN = "t" * 40
    static final String README = "simtest checks the company's test scripts.\n\nRun: simtest check data/<script>.sim"

    def "run_python and run_shell are offered only with the tenant's sandbox entered and answering: #reason"() {
        given:
        def setup = chatSetup(withSandbox)
        sandboxes.up = up

        when:
        api.ask(setup.admin, [modelId: setup.model.id, message: "Hallo"])
        def first = fake.chats.first()

        then:
        first.tools.containsAll(["run_python", "run_shell", "view_images", "hand_out_file"]) == offered
        (first.messages.first() as SystemMessage).text().contains("run_python") == offered
        (first.messages.first() as SystemMessage).text().contains("view_images") == offered
        (first.messages.first() as SystemMessage).text().contains("hand_out_file") == offered

        where:
        reason                              | withSandbox | up    | offered
        "entered, it answers"               | true        | true  | true
        "entered, it does not answer"       | true        | false | false
        "none entered for the tenant"       | false       | true  | false
    }

    def "the README of the tenant's own sandbox image follows the code rules as it is - only when it reports one: #reason"() {
        given:
        def setup = chatSetup(withSandbox)
        sandboxes.up = up
        sandboxes.extras = readme

        when:
        api.ask(setup.admin, [modelId: setup.model.id, message: "Prüfe mein Testskript"])
        def prompt = (fake.chats.first().messages.first() as SystemMessage).text()

        then:
        prompt.contains("Programs installed in this sandbox (its README):\n" + README) == shown
        prompt.contains("Programs installed in this sandbox") == shown

        where:
        reason                                 | withSandbox | up    | readme || shown
        "an image built on ours with a README" | true        | true  | README || true
        "our image, without one"               | true        | true  | null   || false
        "the sandbox does not answer"          | true        | false | README || false
        "no sandbox entered"                   | false       | true  | README || false
    }

    def "the README is not given when the tenant's instances report different ones - a session may land on either"() {
        given:
        def erste = address("erste")
        def zweite = address("zweite")
        def setup = chatSetup(true, erste + "," + zweite, TOKEN)
        sandboxes.up = true
        sandboxes.extras = README
        sandboxes.extrasOf[zweite] = null

        when:
        api.ask(setup.admin, [modelId: setup.model.id, message: "Prüfe mein Testskript"])

        then: "the code tools still, without the README"
        fake.chats.first().tools.contains("run_python")
        !(fake.chats.first().messages.first() as SystemMessage).text().contains("Programs installed in this sandbox")
    }

    def "two tenants: each answer's code runs on its own tenant's instances with its token"() {
        given:
        def erste = address("erste")
        def zweite = address("zweite")
        def zweite2 = address("zweite-2")
        def first = chatSetup(true, erste, "a" * 40)
        def second = chatSetup(true, zweite + "," + zweite2, "b" * 40)
        sandboxes.up = true

        when:
        api.ask(first.admin, [modelId: first.model.id, message: "Rechne [[python 1 + 1]]"])
        api.ask(second.admin, [modelId: second.model.id, message: "Rechne [[python 2 + 2]]"])

        then:
        sandboxes.sessions*.urls == [[erste], [zweite, zweite2]]
        sandboxes.sessions*.token == ["a" * 40, "b" * 40]
    }

    def "the client software's code runs in one session with every file sent, which ends with the answer"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        def log = (1..100).collect { "2026-10-07 06:00:${it} INFO cycle ${it}" }.join("\n")

        when:
        def response = api.call(apiKey(setup.tenant), "POST", "/api/v1/chat", [modelId: setup.model.id,
                message: "Prüfe [[python len(lines)]] [[shell grep -c ERROR data/machine.log]]",
                files: [[name: "notiz.txt", content: "Schicht B"], [name: "machine.log", content: log]]])

        then: "one session, with the small file and the large one"
        sandboxes.sessions.size() == 1
        def session = sandboxes.sessions.first()
        session.files == ["notiz.txt": "Schicht B", "machine.log": log]

        and: "Python and the shell ran in it, the model got their output"
        session.calls == ["python: len(lines)", "shell: grep -c ERROR data/machine.log"]
        response.answer.content.contains("python: len(lines) [files: machine.log, notiz.txt]")
        response.answer.content.contains("Exit code 0\nshell: grep -c ERROR data/machine.log")

        and: "the session ended with the answer"
        session.closed
    }

    def "ask_assistant over the tenant's MCP server runs code in the asked tenant's own sandbox - whoever asks"() {
        given: "a tenant with its sandbox, its default model and an MCP key - as a topic a general chat asks"
        def setup = chatSetup(true)
        sandboxes.up = true
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: "DOCUMENTS_ONLY",
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5, defaultModelId: setup.model.id])
        def key = (api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Allgemeiner Chat"]) as Map).plainKey as String

        when:
        def result = client.toBlocking().retrieve(HttpRequest.POST("/mcp", [jsonrpc: "2.0", id: 1, method: "tools/call",
                params: [name: "ask_assistant", arguments: [question: "Rechne [[python 6*7]]"]]]).bearerAuth(key), Map).result

        then: "the sandbox tools were offered and the code ran in this tenant's session, which ended with the answer"
        !result.isError
        fake.chats.first().tools.containsAll(["run_python", "run_shell"])
        sandboxes.sessions.size() == 1
        sandboxes.sessions.first().calls == ["python: 6*7"]
        sandboxes.sessions.first().closed
    }

    def "a user's code gets the conversation's files, and the session ends with the streamed answer"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        def uploaded = api.chatUpload(setup.admin, ["preise.csv": "Artikel;Preis\nA;3\nB;4".getBytes("UTF-8")])

        when:
        def answer = api.ask(setup.admin, [conversationId: uploaded.conversation.id, modelId: setup.model.id,
                                           message: "Summe? [[python 3 + 4]]"])

        then:
        sandboxes.sessions.size() == 1
        sandboxes.sessions.first().files == ["preise.csv": "Artikel;Preis\nA;3\nB;4"]
        answer.answer.content.contains("python: 3 + 4 [files: preise.csv]")
        sandboxes.sessions.first().closed
    }

    def "the session ends also when the answer fails"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        fake.failAfterTool = true

        when:
        def events = api.askEvents(setup.admin, [modelId: setup.model.id, message: "Rechne [[python 1 + 1]]"])

        then:
        events.last().event == "error"
        sandboxes.sessions.first().closed
    }

    def "a session the instance ended by itself is replaced by a new one for the next call"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true

        when:
        api.ask(setup.admin, [modelId: setup.model.id, message: "Rechne [[python gone]] [[python 1 + 1]]"])
        def results = fake.chats.last().messages.findAll { it instanceof ToolExecutionResultMessage }
                .collect { (it as ToolExecutionResultMessage).text() }

        then: "the first call is told, the second runs in a new session; both sessions ended"
        results[0].contains("the session ended")
        results[1].contains("python: 1 + 1")
        sandboxes.sessions.size() == 2
        sandboxes.sessions.every { it.closed }
    }

    def "what the model gets back: #reason"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        sandboxes.busy = busy
        sandboxes.tokenRefused = refused == "token"
        sandboxes.filesRefused = refused == "files"

        when:
        api.ask(setup.admin, [modelId: setup.model.id, message: "Rechne [[python ${code}]]".toString()])
        def result = fake.chats.last().messages.findAll { it instanceof ToolExecutionResultMessage }
                .collect { (it as ToolExecutionResultMessage).text() }.first()

        then:
        result.contains(expected)

        where:
        reason                                    | busy  | refused | code     | expected
        "every sandbox busy: go on without it"    | true  | null    | "1 + 1"  | "Error: the code execution is busy right now"
        "the token refused: an admin checks it"   | false | "token" | "1 + 1"  | "Error: the code execution refused the tenant's token - an admin checks the sandbox"
        "a file refused: not busy, too large"     | false | "files" | "1 + 1"  | "Error: the code execution refused a file of this question"
        "an error comes back as text"             | false | null    | "fail()" | "Error:\nTraceback: ZeroDivisionError"
        "a long output is cut"                    | false | false   | "long()" | "more characters cut"
    }

    def "the client software without history gets view_images, but no hand_out_file - its files go nowhere"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true

        when:
        api.call(apiKey(setup.tenant), "POST", "/api/v1/chat", [modelId: setup.model.id, message: "Hallo"])

        then:
        fake.chats.first().tools.containsAll(["run_python", "view_images"])
        !fake.chats.first().tools.contains("hand_out_file")

        and: "nor does its prompt send the model to it"
        def prompt = (fake.chats.first().messages.first() as SystemMessage).text()
        prompt.contains("view_images")
        !prompt.contains("hand_out_file")
    }

    def "view_images shows the model the images its code wrote, with their size in pixels; what it cannot show it is told"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        sandboxes.written["out/frame_020.png"] = png(1280, 720)
        sandboxes.written["out/panorama.png"] = png(4000, 1000)
        sandboxes.written["out/notiz.jpg"] = "kein Bild".bytes

        when:
        def answer = api.ask(setup.admin, [modelId: setup.model.id,
                message: "Schau ab Sekunde 20 [[view out/frame_020.png out/panorama.png out/notiz.jpg out/fehlt.png]]"])
        def result = toolResults().first()
        def labels = fake.chats.last().messages.findAll { it instanceof UserMessage && FakeAiModels.isToolImages(it) }
                .collectMany { (it as UserMessage).contents().findAll { c -> c instanceof TextContent }*.text() }

        then: "two images reach the model, each named with its size - a large one with a word that it is shown smaller"
        answer.answer.content.contains("(Bilder: 2)")
        result.startsWith("2 image(s) follow the tool results.")
        labels.contains('"out/frame_020.png" (1280x720 pixels):')
        labels.contains('"out/panorama.png" (4000x1000 pixels, shown smaller - give positions in its own 4000x1000 pixels):')

        and: "the file that is no image and the missing one are named for the model"
        result.contains("out/notiz.jpg: not an image that can be shown")
        result.contains('out/fehlt.png: there is no file "out/fehlt.png" in the session')

        and: "they were read in the answer's one session, which ended with it"
        sandboxes.sessions.size() == 1
        sandboxes.sessions.first().calls == ["file: out/frame_020.png", "file: out/panorama.png", "file: out/notiz.jpg",
                                             "file: out/fehlt.png"]
        sandboxes.sessions.first().closed
    }

    def "view_images: #reason"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true

        when:
        api.ask(setup.admin, [modelId: setup.model.id, message: "Schau [[view ${paths}]]".toString()])

        then:
        toolResults().first().startsWith(expected)
        sandboxes.sessions.every { it.calls.isEmpty() }

        where:
        reason                        | paths                                            || expected
        "more than 12 images at once" | (1..13).collect { "out/f${it}.png" }.join(" ")   || "Error: at most 12 images per call"
    }

    def "hand_out_file stores what the code wrote as a file of the answer - a marked still shown as an image, a clip"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        def still = png(640, 360)
        def clip = "mp4-bytes".bytes
        sandboxes.written["out/marked.png"] = still
        sandboxes.written["out/clips/lauf.mp4"] = clip

        when:
        def answer = api.ask(setup.admin, [modelId: setup.model.id,
                message: "Zeichne ein [[hand_out out/marked.png kurve-3-korrektur]] [[hand_out out/clips/lauf.mp4]]"])
        def files = answer.answer.files.sort { it.fileName }

        then: "both are files of the answer, typed by their extension - the still as an image the chat shows"
        files*.fileName == ["kurve-3-korrektur.png", "lauf.mp4"]
        files*.contentType == ["image/png", "video/mp4"]
        files*.origin == ["GENERATED", "GENERATED"]
        files[0].imageCount == 1
        api.download(setup.admin, "/tenant/chat/files/${files[0].id}/content").body() == still
        api.download(setup.admin, "/tenant/chat/files/${files[1].id}/content").body() == clip

        and: "the model is told the application shows them"
        toolResults().every { it.contains("was created") }
    }

    def "a clip handed out goes into the next answer's sandbox; read_file sends the model to the code tools for it, like any video"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        sandboxes.written["out/lauf.mp4"] = "mp4-bytes".bytes
        def first = api.ask(setup.admin, [modelId: setup.model.id, message: "Schneide [[hand_out out/lauf.mp4]]"])

        when: "the next answer runs code"
        api.ask(setup.admin, [conversationId: first.conversation.id, modelId: setup.model.id,
                              message: "Noch einmal [[python 1]]"])

        then:
        sandboxes.sessions.last().files == ["lauf.mp4": "mp4-bytes"]

        when: "the next one reads the conversation's files"
        api.ask(setup.admin, [conversationId: first.conversation.id, modelId: setup.model.id, message: "Und?"])

        then:
        toolResults().any { it.contains('"lauf.mp4" is a video - no model reads it as it is. Examine it with code') }
    }

    def "hand_out_file: #reason"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        sandboxes.written["out/gross.mp4"] = new byte[50 * 1024 * 1024 + 1]

        when:
        def answer = api.ask(setup.admin, [modelId: setup.model.id, message: "Gib her ${call}".toString()])

        then:
        toolResults().first().startsWith("Error: ")
        toolResults().first().contains(expected)
        answer.answer.files.isEmpty()

        where:
        reason                   | call                          || expected
        "a file that is not there" | "[[hand_out out/fehlt.png]]"  || 'there is no file "out/fehlt.png"'
        "a file above 50 MB"     | "[[hand_out out/gross.mp4]]"  || "at most"
    }

    def "hand_out_file hands out a movement's stills in one call, in their order - the chat shows them as a sequence"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        (1..12).each { sandboxes.written["out/still_${String.format('%02d', it)}.png".toString()] = png(64, 48) }
        def paths = (1..12).collect { "out/still_${String.format('%02d', it)}.png" }

        when:
        def answer = api.ask(setup.admin, [modelId: setup.model.id, message: "Zeig die Phasen [[hand_out_all ${paths.join(' ')}]]".toString()])

        then: "twelve images - more than the five other files an answer may have -, in their order, from one call"
        answer.answer.files*.fileName == (1..12).collect { "still_${String.format('%02d', it)}.png" }
        answer.answer.files.every { it.contentType == "image/png" && it.origin == "GENERATED" }
        toolResults().size() == 1
        toolResults().first().startsWith("12 files (\"still_01.png\"")
        toolResults().first().contains("images as one sequence with a slider")
    }

    def "an answer shows the model at most 60 images - each later round sends them all again, a provider refuses too many"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        def calls = (1..6).collect { call -> (1..12).collect { "out/c${call}_${it}.png".toString() } }
        calls.flatten().each { sandboxes.written[it as String] = png(8, 8) }

        when:
        api.ask(setup.admin, [modelId: setup.model.id,
                message: calls.collect { "[[view ${it.join(' ')}]]" }.join(" ").toString()])
        def results = toolResults()

        then: "five calls show their twelve, the sixth is refused before the sandbox is asked"
        results.take(5).every { it.startsWith("12 image(s) follow the tool results.") }
        results[5] == "Error: at most 60 images per answer, 0 left - answer with what you have seen"
        sandboxes.sessions.first().calls.size() == 60
    }

    def "an answer hands out at most 30 images - the rest is named for the model, the others are kept"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        def paths = (1..31).collect { "out/f${it}.png".toString() }
        paths.each { sandboxes.written[it] = png(8, 8) }

        when:
        def answer = api.ask(setup.admin, [modelId: setup.model.id, message: "Alle [[hand_out_all ${paths.join(' ')}]]".toString()])

        then:
        answer.answer.files.size() == 30
        toolResults().first().contains('Not handed out: out/f31.png: at most 30 images per answer')
    }

    def "the name a handed-out file gets: #requested"() {
        expect:
        de.alpdesk.services.tools.HandOutFileTool.name(path, requested) == expected

        where:
        path                 | requested         || expected
        "out/marked.png"     | null              || "marked.png"
        "out/marked.png"     | "kurve 3"         || "kurve 3.png"
        "out/marked.png"     | "kurve.jpg"       || "kurve.jpg"
        "/work/x/out/clip"   | "clip"            || "clip"
        "out\\win.png"       | null              || "win.png"
    }

    private List<String> toolResults() {
        fake.chats.last().messages.findAll { it instanceof ToolExecutionResultMessage }
                .collect { (it as ToolExecutionResultMessage).text() }
    }

    private static byte[] png(int width, int height) {
        def out = new ByteArrayOutputStream()
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out)
        out.toByteArray()
    }

    /** An address of its own for every tenant - one entered at two tenants is refused. */
    private static String address(String name) {
        "http://alpdesk-ai-sandbox-${name}-${UUID.randomUUID().toString().substring(0, 8)}:8080"
    }

    private Map chatSetup(boolean withSandbox, String urls = address("muster"), String token = TOKEN) {
        def model = api.chatModel()
        def tenant = api.tenant()
        api.allow(tenant.id, model)
        if (withSandbox) {
            api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, sandboxUrls: urls, sandboxToken: token])
        }
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-anthropic-key-1"])
        [model: model, tenant: tenant, admin: admin]
    }

    private String apiKey(Map tenant) {
        api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Fachsoftware", keepsHistory: false]).plainKey
    }
}
