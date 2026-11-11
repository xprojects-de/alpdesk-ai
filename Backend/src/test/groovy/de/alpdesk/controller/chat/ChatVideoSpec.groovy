package de.alpdesk.controller.chat

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.chat.ChatFileCleanupJob
import de.alpdesk.services.sandbox.FakeSandboxes
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Videos in the chat (docs/architecture/code-execution.md "Videos"): stored as they are - one above the 50 MB of every
 * other file streamed, up to app.chat.video-max-size (60 MB in the specs) -, refused for a tenant without a sandbox,
 * examined with code; deleted from the storage after the retention time, their entry kept and marked expired.
 */
@MicronautTest(transactional = false)
class ChatVideoSpec extends Specification {

    static final long MB = 1024 * 1024
    static final Path STORAGE = Path.of("build/test-storage")

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    FakeSandboxes sandboxes

    @Inject
    ChatFileCleanupJob cleanup

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
        sandboxes.reset()
    }

    def "a video is stored as it is, without a copy for the models - one above 50 MB streamed"() {
        given:
        def setup = chatSetup(true)
        def small = mp4(MB)
        def large = mp4(55 * MB)

        when:
        def first = api.chatUpload(setup.admin, ["lauf.mp4": small])
        def second = api.chatUpload(setup.admin, ["lauf-lang.mp4": large], first.conversation.id)

        then:
        [first.files[0], second.files[0]]*.contentType == ["video/mp4", "video/mp4"]
        [first.files[0], second.files[0]]*.sizeBytes == [MB, 55 * MB]
        [first.files[0], second.files[0]]*.expired == [false, false]
        api.download(setup.admin, "/tenant/chat/files/${first.files[0].id}/content").body() == small
        storedSizes(setup.tenant) == [MB, 55 * MB]
        Files.readAllBytes(stored(setup.tenant).find { Files.size(it) == 55 * MB }) == large

        and: "nothing but the file itself is stored - no copy for a model"
        storedNames(setup.tenant) == ["file", "file"]
    }

    def "the client software's upload takes a video the same way"() {
        given:
        def setup = chatSetup(true)
        def key = api.admin("POST", "/admin/tenants/${setup.tenant.id}/api-keys", [name: "Fachsoftware", keepsHistory: true]).plainKey

        expect:
        api.chatUpload(key, ["lauf.mp4": mp4(MB)], null, "/api/v1/chat/files").files[0].contentType == "video/mp4"
    }

    def "a video is refused: #reason"() {
        given:
        def setup = chatSetup(withSandbox)

        when:
        def refusal = refused(setup.admin, files)

        then:
        refusal.status == status
        refusal.code == code

        and: "nothing was stored"
        storedNames(setup.tenant).isEmpty()

        where:
        reason                                         | withSandbox | files                                              || status                 | code
        "the tenant has no sandbox to examine it"      | false       | ["lauf.mp4": mp4(MB)]                              || HttpStatus.CONFLICT    | "chatFile.videoNeedsSandbox"
        "one larger than the video size"               | true        | ["lauf.mp4": mp4(61 * MB)]                         || HttpStatus.BAD_REQUEST | "chatFile.tooLarge"
        "a file above 50 MB that is no video"          | true        | ["gross.pdf": ("%PDF-1.7\n" + "x" * (51 * MB)).bytes] || HttpStatus.BAD_REQUEST | "chatFile.tooLarge"
        "one of several files, also the others"        | false       | ["notiz.txt": "Hallo".bytes, "lauf.mp4": mp4(MB)]  || HttpStatus.CONFLICT    | "chatFile.videoNeedsSandbox"
        "two videos above the video size together"     | true        | ["a.mp4": mp4(55 * MB), "b.mp4": mp4(10 * MB)]     || HttpStatus.CONFLICT    | "chatFile.videosTooLarge"
    }

    def "the videos of one conversation keep to the video size together - an expired one no longer counts"() {
        given: "a video of 55 MB in a conversation - the session holds it and the frames cut from it"
        def setup = chatSetup(true)
        def conversationId = api.chatUpload(setup.admin, ["lauf.mp4": mp4(55 * MB)]).conversation.id

        expect: "a second one would not fit beside it - another conversation takes it"
        api.chatUploadStatus(setup.admin, ["zweiter-lauf.mp4": mp4(10 * MB)], conversationId) == HttpStatus.CONFLICT
        api.chatUploadStatus(setup.admin, ["zweiter-lauf.mp4": mp4(10 * MB)]) == HttpStatus.CREATED

        and: "other files still come into the conversation"
        api.chatUploadStatus(setup.admin, ["notiz.txt": "Kurve 3".bytes], conversationId) == HttpStatus.CREATED

        when: "the first video expires"
        cleanup.expire(Instant.now().plus(Duration.ofHours(9)))

        then:
        api.chatUploadStatus(setup.admin, ["zweiter-lauf.mp4": mp4(10 * MB)], conversationId) == HttpStatus.CREATED
    }

    def "read_file sends the model to the code tools for a video, which lies in the sandbox's data/"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        def uploaded = api.chatUpload(setup.admin, ["lauf.mp4": mp4(1024)])

        when: "the model reads the conversation's files"
        api.ask(setup.admin, [conversationId: uploaded.conversation.id, modelId: setup.model.id, message: "Schau ab Sekunde 20"])

        then: "no model gets the video itself - it is sent to the code tools"
        toolResults().any { it.contains('"lauf.mp4" is a video - no model reads it as it is. Examine it with code') }
        !fake.chats.last().messages.any { it instanceof UserMessage && FakeAiModels.isToolImages(it) }

        when: "its code runs"
        api.ask(setup.admin, [conversationId: uploaded.conversation.id, modelId: setup.model.id, message: "Schneide [[python 1]]"])

        then:
        sandboxes.sessions.first().files.keySet() == ["lauf.mp4"] as Set
    }

    def "the model gets the rules for looking at a video only with one in the conversation and the code tools: #reason"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = up
        def uploaded = api.chatUpload(setup.admin, files)
        if (expired) {
            cleanup.expire(Instant.now().plus(Duration.ofHours(9)))
        }

        when:
        api.ask(setup.admin, [conversationId: uploaded.conversation.id, modelId: setup.model.id, message: "Ab Sekunde 20"])
        def prompt = (fake.chats.first().messages.first() as dev.langchain4j.data.message.SystemMessage).text()

        then:
        prompt.contains("A video of this conversation lies in data/") == rules
        prompt.contains("Where the user names a time or a passage, start there") == rules
        prompt.contains("from rtmlib import BodyWithFeet") == rules
        prompt.contains("Show the movement as a sequence") == rules
        prompt.contains("Do what the user asks - cut, join, slow") == rules
        prompt.contains("When the user wants a person's movement examined") == rules
        prompt.contains("timeout 60 ffmpeg -nostdin") == rules

        where:
        reason                               | files                                  | up    | expired || rules
        "a video, the sandbox answers"       | ["lauf.mp4": mp4(1024)]                | true  | false   || true
        "no video"                           | ["notiz.txt": "Kurve 3".bytes]         | true  | false   || false
        "a video, the sandbox does not answer" | ["lauf.mp4": mp4(1024)]              | false | false   || false
        "a video that expired"               | ["lauf.mp4": mp4(1024)]                | true  | true    || false
    }

    def "an answer about a video may take up to 16 rounds - the last must answer; without a video 6: #reason"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = up
        def uploaded = api.chatUpload(setup.admin, files)

        when:
        api.ask(setup.admin, [conversationId: uploaded.conversation.id, modelId: setup.model.id,
                              message: "Schau genau [[shell-forever]]"])

        then: "the round before the last is told it is the last with tools"
        fake.chats.size() == rounds
        fake.chats.last().tools.isEmpty()
        lastToolRoundNotes(fake.chats[rounds - 2]) == 1
        fake.chats[0..rounds - 3].every { lastToolRoundNotes(it) == 0 }

        where:
        reason                                 | files                          | up    || rounds
        "a video and the code tools"           | ["lauf.mp4": mp4(1024)]        | true  || 16
        "no video"                             | ["notiz.txt": "Kurve 3".bytes] | true  || 6
    }

    def "a sandbox that is not the video image: #reason"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = up
        sandboxes.video = video

        when:
        def refusal = refused(setup.admin, ["lauf.mp4": mp4(1024)])

        then:
        refusal.status == status
        refusal.code == code

        where:
        reason                                                        | up    | video || status                 | code
        "the upload is refused - nothing could examine it"           | true  | false || HttpStatus.CONFLICT    | "chatFile.videoNeedsVideoSandbox"
        "the video image takes it"                                    | true  | true  || HttpStatus.CREATED     | null
        "not answering right now: taken, as with a sandbox entered"   | false | false || HttpStatus.CREATED     | null
    }

    def "a video uploaded before the tenant's sandbox lost its video tools: no rules, no extra rounds, read_file says so"() {
        given:
        def setup = chatSetup(true)
        def uploaded = api.chatUpload(setup.admin, ["lauf.mp4": mp4(1024)])
        sandboxes.up = true
        sandboxes.video = false

        when:
        api.ask(setup.admin, [conversationId: uploaded.conversation.id, modelId: setup.model.id, message: "Schau [[shell-forever]]"])
        def prompt = (fake.chats.first().messages.first() as dev.langchain4j.data.message.SystemMessage).text()

        then:
        !prompt.contains("A video of this conversation lies in data/")
        fake.chats.size() == 6

        when:
        fake.reset()
        api.ask(setup.admin, [conversationId: uploaded.conversation.id, modelId: setup.model.id, message: "Und?"])

        then:
        toolResults().any { it.contains("this company's code execution has no video tools") }
    }

    def "a video whose sandbox does not answer right now is named for the model to tell the user"() {
        given:
        def setup = chatSetup(true)
        def uploaded = api.chatUpload(setup.admin, ["lauf.mp4": mp4(1024)])

        when:
        api.ask(setup.admin, [conversationId: uploaded.conversation.id, modelId: setup.model.id, message: "Und?"])

        then:
        toolResults().any { it.contains("the code execution it is examined with is not available right now") }
    }

    def "after its retention time a video is deleted from the storage; its entry stays, marked expired"() {
        given:
        def setup = chatSetup(true)
        sandboxes.up = true
        sandboxes.written["out/still.png"] = "png".bytes
        def uploaded = api.chatUpload(setup.admin, ["lauf.mp4": mp4(1024), "notiz.txt": "Kurve 3".bytes])
        def conversationId = uploaded.conversation.id
        api.ask(setup.admin, [conversationId: conversationId, modelId: setup.model.id, message: "Gib [[hand_out out/still.png]]"])

        when: "the job runs within the 8 hours, and after them"
        cleanup.expire(Instant.now().plus(Duration.ofHours(7)))
        def within = files(setup.admin, conversationId)
        cleanup.expire(Instant.now().plus(Duration.ofHours(9)))
        def after = files(setup.admin, conversationId)

        then: "within, nothing changed; after, only the uploaded video expired - the text and the still handed out stay (14 days)"
        within.every { !it.expired }
        after.collectEntries { [(it.fileName): it.expired] } == ["lauf.mp4": true, "notiz.txt": false, "still.png": false]

        and: "its content is gone, the others are still there"
        def video = after.find { it.fileName == "lauf.mp4" }
        downloadStatus(setup.admin, video.id) == HttpStatus.NOT_FOUND
        downloadStatus(setup.admin, after.find { it.fileName == "notiz.txt" }.id) == HttpStatus.OK

        when: "the conversation goes on"
        fake.reset()
        api.ask(setup.admin, [conversationId: conversationId, modelId: setup.model.id, message: "Noch einmal [[python 1]]"])

        then: "the model is told it expired, and its code no longer finds it"
        (fake.chats.first().messages.findAll { it instanceof UserMessage && !FakeAiModels.isToolImages(it) }.last() as UserMessage).singleText()
                .contains('"lauf.mp4" (video/mp4, 1024 bytes, expired - deleted after its retention time; ask the user to upload it again)')
        sandboxes.sessions.last().files.keySet() == ["notiz.txt", "still.png"] as Set

        when: "it reads the files"
        api.ask(setup.admin, [conversationId: conversationId, modelId: setup.model.id, message: "Und?"])

        then:
        toolResults().any { it.contains('"lauf.mp4" expired - deleted after its retention time; ask the user to upload it again') }
    }

    def "deleting the conversation deletes the video from the storage at once"() {
        given:
        def setup = chatSetup(true)
        def uploaded = api.chatUpload(setup.admin, ["lauf.mp4": mp4(1024)])

        when:
        api.status(setup.admin, "DELETE", "/tenant/chat/conversations/${uploaded.conversation.id}") == HttpStatus.NO_CONTENT

        then:
        storedNames(setup.tenant).isEmpty()
    }

    def "another tenant's user neither sees nor downloads a video"() {
        given:
        def owner = chatSetup(true)
        def other = chatSetup(true)
        def uploaded = api.chatUpload(owner.admin, ["lauf.mp4": mp4(1024)])

        expect:
        downloadStatus(other.admin, uploaded.files[0].id) == HttpStatus.NOT_FOUND
        api.status(other.admin, "GET", "/tenant/chat/conversations/${uploaded.conversation.id}/files") == HttpStatus.NOT_FOUND
    }

    private static int lastToolRoundNotes(Map chat) {
        chat.messages.count { it instanceof UserMessage && (it as UserMessage).hasSingleText()
                && (it as UserMessage).singleText() == de.alpdesk.services.agent.AgentLoop.LAST_TOOL_ROUND }
    }

    /** An MP4 as Tika recognises it - its "ftyp" box - filled up to the size. */
    static byte[] mp4(long size) {
        byte[] bytes = new byte[size as int]
        def box = new ByteArrayOutputStream()
        box.write([0, 0, 0, 0x18] as byte[])
        box.write("ftypisom".bytes)
        box.write([0, 0, 2, 0] as byte[])
        box.write("isomiso2".bytes)
        System.arraycopy(box.toByteArray(), 0, bytes, 0, box.size())
        bytes
    }

    private Map refused(String token, Map<String, byte[]> files) {
        def body = MultipartBody.builder()
        files.each { name, content -> body.addPart("files", name, MediaType.APPLICATION_OCTET_STREAM_TYPE, content) }
        try {
            client.toBlocking().exchange(HttpRequest.POST("/tenant/chat/files", body.build())
                    .contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token), Map)
            [status: HttpStatus.CREATED]
        } catch (HttpClientResponseException e) {
            [status: e.status, code: e.response.getBody(Map).map { it.code }.orElse(null)]
        }
    }

    private List<Map> files(String token, Object conversationId) {
        api.call(token, "GET", "/tenant/chat/conversations/${conversationId}/files") as List<Map>
    }

    private HttpStatus downloadStatus(String token, Object fileId) {
        try {
            api.download(token, "/tenant/chat/files/${fileId}/content").status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    private List<String> toolResults() {
        fake.chats.last().messages.findAll { it instanceof ToolExecutionResultMessage }
                .collect { (it as ToolExecutionResultMessage).text() }
    }

    /** The names of the files the tenant's chat has in the storage, e.g. ["file", "media"]. */
    private static List<String> storedNames(Map tenant) {
        stored(tenant)*.fileName*.toString().sort()
    }

    private static List<Long> storedSizes(Map tenant) {
        stored(tenant).collect { Files.size(it) }.sort()
    }

    private static List<Path> stored(Map tenant) {
        Path chat = STORAGE.resolve("tenants/${tenant.id}/chat")
        if (!Files.isDirectory(chat)) {
            return []
        }
        Files.walk(chat).withCloseable { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
    }

    private Map chatSetup(boolean withSandbox) {
        def model = api.chatModel()
        def tenant = api.tenant()
        api.allow(tenant.id, model)
        if (withSandbox) {
            api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, sandboxToken: "t" * 40,
                    sandboxUrls: "http://alpdesk-ai-sandbox-${UUID.randomUUID().toString().substring(0, 8)}:8080".toString()])
        }
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-anthropic-key-1"])
        [model: model, tenant: tenant, admin: admin]
    }
}
