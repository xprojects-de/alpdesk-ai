package de.alpdesk.controller.chat

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.chat.ChatFileCleanupJob
import de.alpdesk.services.common.Retention
import de.alpdesk.storage.FileStorage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.micronaut.context.ApplicationContext
import io.micronaut.context.exceptions.BeanInstantiationException
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * How long the chat's files stay (docs/architecture/code-execution.md "Videos", "Retention"): every file but an uploaded
 * video app.chat.file-retention-days (14) after it was stored - uploaded and created alike -, then deleted from the
 * storage with its text; its entry stays, marked expired. Left empty, a retention is off.
 */
@MicronautTest(transactional = false)
class ChatFileRetentionSpec extends Specification {

    static final Path STORAGE = Path.of("build/test-storage")

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    ChatFileCleanupJob cleanup

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "after 14 days every file is deleted with its text - uploaded and created alike; its entry stays, marked expired"() {
        given:
        def setup = chatSetup()
        def uploaded = api.chatUpload(setup.token, ["preise.csv": "Artikel;Preis\nA;3".bytes])
        def conversationId = uploaded.conversation.id
        api.ask(setup.token, [conversationId: conversationId, modelId: setup.model.id,
                              message: "Liste [[create_file liste.txt TXT]]"])

        when: "the job runs within the 14 days, and after them"
        cleanup.expire(Instant.now().plus(Duration.ofDays(13)))
        def within = files(setup.token, conversationId)
        cleanup.expire(Instant.now().plus(Duration.ofDays(15)))
        def after = files(setup.token, conversationId)

        then:
        within.every { !it.expired }
        after.collectEntries { [(it.fileName): it.expired] } == ["preise.csv": true, "liste.txt": true]

        and: "nothing of them is left in the storage, and the download says why"
        stored(setup.tenant).isEmpty()
        refusal(setup.token, after[0].id) == [status: HttpStatus.NOT_FOUND, code: "chatFile.expired"]

        when: "the conversation goes on"
        fake.reset()
        api.ask(setup.token, [conversationId: conversationId, modelId: setup.model.id, message: "Und die Preise?"])
        def question = (fake.chats.first().messages.findAll { it instanceof UserMessage && !FakeAiModels.isToolImages(it) }
                .last() as UserMessage).singleText()
        def results = fake.chats.last().messages.findAll { it instanceof ToolExecutionResultMessage }
                .collect { (it as ToolExecutionResultMessage).text() }

        then: "the model is told - the upload to be uploaded again, the created file only that it is gone - and reads no text of them"
        question.contains('"preise.csv" (text/csv, 17 bytes, expired - deleted after its retention time; ask the user to upload it again)')
        question.contains('"liste.txt" (text/plain')
        question.contains('expired - deleted after its retention time)')
        results.any { it.contains('"preise.csv" expired - deleted after its retention time; ask the user to upload it again') }
        !results.any { it.contains("Artikel;Preis") }
    }

    def "the job reads every tenant over a connection of its own - no tenant's pool is opened, none stays connected"() {
        given: "a tenant with a file, whose pool is closed again - as for most tenants when the job runs"
        def setup = chatSetup()
        def uploaded = api.chatUpload(setup.token, ["notiz.txt": "Hallo".bytes])
        String databaseName = cleanup.tenants.findById(setup.tenant.id as Long).orElseThrow().databaseName()
        cleanup.databases.closePool(databaseName)

        when:
        cleanup.expire(Instant.now().plus(Duration.ofDays(15)))

        then: "through no pool: every tenant's would keep a connection open for half a minute - hundreds the server's last"
        !cleanup.databases.pools.containsKey(databaseName)
        otherConnections(databaseName) == 0

        and: "the file expired all the same"
        files(setup.token, uploaded.conversation.id).first().expired
    }

    def "a tenant whose files cannot be deleted right now stops no other tenant, and is done at the next run: the #failing fails"() {
        given:
        def first = chatSetup()
        def second = chatSetup()
        def firstUpload = api.chatUpload(first.token, ["notiz.txt": "Hallo".bytes])
        def secondUpload = api.chatUpload(second.token, ["notiz.txt": "Hallo".bytes])
        def (broken, brokenUpload, working, workingUpload) = failing == "first"
                ? [first, firstUpload, second, secondUpload] : [second, secondUpload, first, firstUpload]
        def job = new ChatFileCleanupJob(cleanup.tenants, cleanup.databases, cleanup.files,
                new RefusingStorage(cleanup.storage, "tenants/${broken.tenant.id}/"), "8", "14")

        when:
        job.expire(Instant.now().plus(Duration.ofDays(15)))

        then: "the other tenant's file expired; this one's is still stored and not marked"
        files(working.token, workingUpload.conversation.id).first().expired
        !files(broken.token, brokenUpload.conversation.id).first().expired
        stored(broken.tenant).size() == 1

        when: "the next run, the storage working again"
        cleanup.expire(Instant.now().plus(Duration.ofDays(15)))

        then:
        files(broken.token, brokenUpload.conversation.id).first().expired
        stored(broken.tenant).isEmpty()

        where: "either order of the tenants - whichever the job reads first"
        failing << ["first", "second"]
    }

    def "a file whose content cannot be deleted holds up none of the tenant's other files"() {
        given:
        def setup = chatSetup()
        def stuck = api.chatUpload(setup.token, ["alt.txt": "Hallo".bytes])
        String stuckDir = "tenants/${setup.tenant.id}/chat/${stored(setup.tenant).first().parent.fileName}/"
        def later = api.chatUpload(setup.token, ["neu.txt": "Hallo".bytes])
        def job = new ChatFileCleanupJob(cleanup.tenants, cleanup.databases, cleanup.files,
                new RefusingStorage(cleanup.storage, stuckDir), "8", "14")

        when:
        job.expire(Instant.now().plus(Duration.ofDays(15)))

        then:
        !files(setup.token, stuck.conversation.id).first().expired
        files(setup.token, later.conversation.id).first().expired
        stored(setup.tenant).size() == 1
    }

    def "with a retention left empty, those files are kept"() {
        given:
        def setup = chatSetup()
        def uploaded = api.chatUpload(setup.token, ["notiz.txt": "Hallo".bytes])
        def keeping = new ChatFileCleanupJob(cleanup.tenants, cleanup.databases, cleanup.files, cleanup.storage, "", "")

        when:
        keeping.expire(Instant.now().plus(Duration.ofDays(3650)))

        then:
        !files(setup.token, uploaded.conversation.id).first().expired
        stored(setup.tenant).size() == 1
    }

    def "a retention setting: #setting"() {
        expect:
        Retention.parse("app.chat.file-retention-days", setting, ChronoUnit.DAYS) == expected

        where:
        setting | expected
        "14"    | Optional.of(Duration.ofDays(14))
        " 2 "   | Optional.of(Duration.ofDays(2))
        ""      | Optional.empty()
        null    | Optional.empty()
    }

    def "the start says what the retention in effect is: #note"() {
        expect:
        ChatFileCleanupJob.retentionNote(video, file) == note

        where:
        video                           | file                            || note
        Optional.of(Duration.ofHours(8)) | Optional.of(Duration.ofDays(14)) || "an uploaded video deleted 8 hours after its upload, every other file deleted 14 days after it was stored"
        Optional.empty()                | Optional.of(Duration.ofDays(14)) || "an uploaded video kept, every other file deleted 14 days after it was stored"
        Optional.of(Duration.ofHours(8)) | Optional.empty()                || "an uploaded video deleted 8 hours after its upload, every other file kept"
        Optional.empty()                | Optional.empty()                || "kept - no retention set"
    }

    def "a retention setting that is no positive number stops the start, naming it: #setting"() {
        when:
        Retention.parse("app.chat.video-retention-hours", setting, ChronoUnit.HOURS)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith("app.chat.video-retention-hours must be a number of hours above 0")

        where:
        setting << ["0", "-1", "acht", "8h"]
    }

    def "the job is built when the context starts, not at its first run - a wrong setting stops the start"() {
        when: "a context starts with a typo in the retention"
        ApplicationContext.run(["app.chat.file-retention-days": "14d"], "test").close()

        then:
        def e = thrown(BeanInstantiationException)
        rootCause(e).message.startsWith("app.chat.file-retention-days must be a number of days above 0")
    }

    private static Throwable rootCause(Throwable e) {
        e.cause == null ? e : rootCause(e.cause)
    }

    private List<Map> files(String token, Object conversationId) {
        api.call(token, "GET", "/tenant/chat/conversations/${conversationId}/files") as List<Map>
    }

    private Map refusal(String token, Object fileId) {
        try {
            api.download(token, "/tenant/chat/files/${fileId}/content")
            [status: HttpStatus.OK]
        } catch (HttpClientResponseException e) {
            [status: e.status, code: e.response.getBody(Map).map { it.code }.orElse(null)]
        }
    }

    private static List<Path> stored(Map tenant) {
        Path chat = STORAGE.resolve("tenants/${tenant.id}/chat")
        if (!Files.isDirectory(chat)) {
            return []
        }
        Files.walk(chat).withCloseable { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
    }

    /** The connections to the tenant's database besides the one asking. */
    private int otherConnections(String databaseName) {
        cleanup.databases.openConnection(databaseName).withCloseable { connection ->
            def result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND pid <> pg_backend_pid()")
            result.next()
            result.getInt(1)
        }
    }

    /** The storage, refusing to delete below one prefix - a tenant's files that cannot be deleted at this moment. */
    static class RefusingStorage implements FileStorage {
        @Delegate
        final FileStorage storage
        final String refused

        RefusingStorage(FileStorage storage, String refused) {
            this.storage = storage
            this.refused = refused
        }

        @Override
        void deleteAll(String prefix) {
            if (prefix.startsWith(refused)) {
                throw new UncheckedIOException(new IOException("the storage is not reachable"))
            }
            storage.deleteAll(prefix)
        }
    }

    private Map chatSetup() {
        def model = api.chatModel()
        def tenant = api.tenant()
        api.allow(tenant.id, model)
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "own-anthropic-key-1"])
        [model: model, tenant: tenant, token: token]
    }
}
