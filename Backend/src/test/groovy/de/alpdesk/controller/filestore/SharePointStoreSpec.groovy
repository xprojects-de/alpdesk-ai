package de.alpdesk.controller.filestore

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.document.SourceApi
import de.alpdesk.services.filestore.FakeMicrosoft
import de.alpdesk.services.filestore.FileStoreApi
import de.alpdesk.services.filestore.FileStoreService
import de.alpdesk.services.task.TaskApi
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import static de.alpdesk.services.filestore.FileStoreApi.STORES

/**
 * SharePoint and OneDrive stores (docs/architecture/file-stores.md "SharePoint and OneDrive") against FakeMicrosoft, a
 * server of the spec answering like Entra ID's login and Microsoft Graph: the app's token, the site by its address, its
 * libraries, a folder page by page, a file read from an offset through its download address - without the token -, the
 * refusals of the login and of Graph, throttling, a token expiring during a session. A real Microsoft 365 tenant is
 * tried by hand (docs/architecture/file-stores.md). Runs always: nothing is built or pulled.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-graph", value = "true")
// A task needs a mail server to report to - the GreenMail of TaskApi
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class SharePointStoreSpec extends Specification {

    static final String LOG_FILE = "2026-10-29_interface.log"
    static final String LINE = "2026-10-29 08:00:05 ERROR connection to interface X timed out\n"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FileStoreService service

    @Inject
    FakeMicrosoft microsoft

    @Shared
    AiApi api

    @Shared
    FileStoreApi stores

    def setup() {
        api = api ?: new AiApi(client)
        stores = stores ?: new FileStoreApi(api, client)
        microsoft.reset()
        microsoft.write("projekte-dokumente", "/logs/" + LOG_FILE, LINE)
    }

    def "a SharePoint store has no fingerprint: its first check logs in, finds the site and reads the folder"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = stores.create(admin, sharePoint())

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "OK"
        result.fingerprint == null
        result.entries == [LOG_FILE]
        service.readable(service.findByName(tenant.id as Long, store.name as String).get())

        and: "the token went to Graph, never the secret"
        microsoft.requests.findAll { it.path.startsWith("/v1.0/") }.every { it.authorization?.startsWith("Bearer token-") }

        and: "there is no fingerprint to confirm"
        stores.refusal(admin, "PUT", "${STORES}/${store.id}/fingerprint", [fingerprint: "SHA256:" + "A" * 43]) ==
                [HttpStatus.CONFLICT, "fileStore.noFingerprint"]
    }

    def "a check that fails says why: #reason"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        if (expired) {
            microsoft.secretExpired = true
        }
        def store = stores.create(admin, sharePoint() + settings)

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "FAILED"
        result.errorCode == code
        (result.store as Map).checkError == code

        where:
        reason                  | settings                                                   | expired | code
        "unknown directory"     | [directoryId: "99999999-9999-9999-9999-999999999999"]      | false   | "fileStore.directoryNotFound"
        "unknown app"           | [clientId: "99999999-9999-9999-9999-999999999999"]         | false   | "fileStore.clientRefused"
        "wrong secret"          | [password: "wrong-secret"]                                 | false   | "fileStore.clientRefused"
        "expired secret"        | [:]                                                        | true    | "fileStore.secretExpired"
        "unknown site"          | [siteUrl: "https://musterfirma.sharepoint.com/sites/weg"]  | false   | "fileStore.siteNotFound"
        "site not granted"      | [siteUrl: FakeMicrosoft.SITE_NOT_GRANTED]                  | false   | "fileStore.accessDenied"
        "unknown library"       | [library: "Fehlt"]                                         | false   | "fileStore.libraryNotFound"
        "no such folder"        | [basePath: "/fehlt"]                                       | false   | "fileStore.folderNotFound"
    }

    def "the library is the site's default one unless named - whatever the case"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        microsoft.write("projekte-archiv", "/alt.log", "INFO old\n")

        expect:
        stores.check(admin, stores.create(admin, sharePoint() + [basePath: "/"]).id).entries == ["logs"]
        stores.check(admin, stores.create(admin, sharePoint() + [library: "archiv", basePath: "/"]).id).entries == ["alt.log"]
    }

    def "a OneDrive is entered by its site's address"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        microsoft.write("onedrive-dokumente", "/Notizen/plan.txt", "Plan\n")

        expect:
        stores.check(admin, stores.create(admin, sharePoint() + [siteUrl: FakeMicrosoft.ONEDRIVE, basePath: "/Notizen"]).id)
                .entries == ["plan.txt"]
    }

    def "a folder is listed over every page, with its subfolders - other items (a notebook) are left out"() {
        given:
        def tenant = api.tenant()
        def store = checkedStore(tenant, sharePoint() + [basePath: "/"])
        (1..7).each { microsoft.write("projekte-dokumente", "/viele/datei-${it}.log", "INFO ${it}\n") }
        microsoft.write("projekte-dokumente", "/viele/Notizbuch.one", "")
        microsoft.folder("projekte-dokumente", "/viele/unter")

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        def entries = session.list("/viele")
        session.close()

        then:
        entries*.name() == (1..7).collect { "datei-${it}.log".toString() } + ["unter"]
        entries.findAll { !it.folder() }.every { it.size() == 7 && it.modified() != null }
        entries.find { it.name() == "unter" }.folder()
        microsoft.requests.count { it.path.endsWith("/page") } == 2
    }

    def "a file is read from any offset through its download address - without the token: #variant"() {
        given:
        def tenant = api.tenant()
        def store = checkedStore(tenant, sharePoint())
        microsoft.ignoresRange = whole

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        def all = new String(session.read("/logs/" + LOG_FILE, 0, 10_000), "UTF-8")
        def part = new String(session.read("/logs/" + LOG_FILE, 20, 5), "UTF-8")
        def beyond = session.read("/logs/" + LOG_FILE, 10_000, 5)
        session.close()

        then:
        all == LINE
        part == LINE.substring(20, 25)
        beyond.length == 0
        def downloads = microsoft.requests.findAll { it.path.startsWith("/download/") }
        downloads.size() == 3
        downloads.every { it.authorization == null && it.range != null }

        where:
        variant                          | whole
        "Microsoft answers the range"    | false
        "Microsoft sends the whole file" | true
    }

    def "a task's write uploads the file whole with the token - a file open in Office is locked, a missing folder refused"() {
        given:
        def tenant = api.tenant()
        def store = checkedStore(tenant, sharePoint())
        def session = service.open(tenant.id as Long, store.id as Long)

        when:
        session.write("/logs/rows.csv", "date;gross\n".getBytes("UTF-8"))
        session.write("/logs/rows.csv", "date;gross\n2026-01-24;119,00\n".getBytes("UTF-8"))

        then:
        new String(session.read("/logs/rows.csv", 0, 10_000), "UTF-8") == "date;gross\n2026-01-24;119,00\n"
        def uploads = microsoft.requests.findAll { it.method == "PUT" }
        uploads.size() == 2
        uploads.every { it.authorization?.startsWith("Bearer token-") && it.path.endsWith(":/content") }

        when:
        microsoft.locked << "/logs/rows.csv"
        session.write("/logs/rows.csv", "x\n".getBytes("UTF-8"))

        then:
        def locked = thrown(ServiceException)
        locked.code() == "fileStore.fileLocked"

        when:
        session.write("/fehlt/rows.csv", "x\n".getBytes("UTF-8"))

        then:
        def missing = thrown(ServiceException)
        missing.code() == "fileStore.writeFailed"

        cleanup:
        session?.close()
    }

    def "a download address on another host than SharePoint's is not fetched"() {
        given:
        def tenant = api.tenant()
        def store = checkedStore(tenant, sharePoint())
        microsoft.foreignDownload = true

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        session.read("/logs/" + LOG_FILE, 0, 100)

        then:
        def e = thrown(ServiceException)
        e.code() == "fileStore.readFailed"
        !microsoft.requests.any { it.path.startsWith("/download/") }
    }

    def "throttling is waited for once when Graph asks for a short wait - else the store counts as throttled: #variant"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, sharePoint())
        microsoft.throttle = answers
        microsoft.retryAfter = wait

        expect:
        def result = stores.check(admin, store.id)
        result.status == status
        result.errorCode == code

        where:
        variant                  | answers | wait | status   | code
        "one short wait"         | 1       | "1"  | "OK"     | null
        "throttled twice"        | 2       | "1"  | "FAILED" | "fileStore.throttled"
        "a wait over 30 seconds" | 1       | "60" | "FAILED" | "fileStore.throttled"
    }

    def "a token expiring during a long session is fetched anew"() {
        given:
        def tenant = api.tenant()
        def store = checkedStore(tenant, sharePoint())

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        microsoft.expireTokens()
        def entries = session.list("/logs")
        session.close()

        then:
        entries*.name() == [LOG_FILE]
        microsoft.requests.count { it.path.endsWith("/oauth2/v2.0/token") } >= 2
    }

    def "choosing a folder lists the library's subfolders over HTTP"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = checkedStore(tenant, sharePoint() + [basePath: "/"])
        microsoft.folder("projekte-dokumente", "/Angebote")

        expect:
        (api.call(admin, "GET", "${STORES}/${store.id}/folders") as Map).folders == ["Angebote", "logs"]
    }

    def "a knowledge source syncs a folder of a library into the knowledge"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def store = checkedStore(tenant, sharePoint() + [basePath: "/"])
        def sources = new SourceApi(api, null)

        when:
        def source = sources.source([token: admin, store: store], [folder: "logs"])
        sources.run(admin, source.id)
        def documents = sources.documents(admin, source.id)

        then:
        documents.keySet() == [LOG_FILE] as Set
        documents[LOG_FILE].status == "READY"
    }

    def "a next page that is no page fails the listing - a source deletes none of the files it did not see"() {
        given: "a source on a folder of two pages, read into the knowledge"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def store = checkedStore(tenant, sharePoint() + [basePath: "/"])
        microsoft.folder("projekte-dokumente", "/seiten")
        (1..5).each { microsoft.write("projekte-dokumente", "/seiten/datei-${it}.txt", "Inhalt ${it}\n") }
        def sources = new SourceApi(api, null)
        def source = sources.source([token: admin, store: store], [folder: "seiten"])
        sources.run(admin, source.id)

        when: "a proxy answers the second page with 200 and a page of its own"
        microsoft.brokenNextPage = true
        def run = sources.run(admin, source.id)

        then: "the run failed, every document is still there"
        run.lastError == "fileStore.readFailed"
        run.lastDeleted == 0
        sources.documents(admin, source.id).size() == 5
    }

    def "a store a source reads keeps its site, library and folder - another app may read them with its secret"() {
        given: "a source reading the store"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def store = checkedStore(tenant, sharePoint() + [basePath: "/"])
        new SourceApi(api, null).source([token: admin, store: store], [folder: "logs"])
        def path = "/tenant/admin/file-stores/${store.id}"
        def settings = sharePoint() + [name: store.name, basePath: "/", password: null]

        expect: "another site, library or folder holds other files - the source's next listing would delete its documents"
        stores.refusal(admin, "PUT", path, settings + [siteUrl: "https://musterfirma.sharepoint.com/sites/archiv"]) ==
                [HttpStatus.CONFLICT, "fileStore.usedBySource"]
        stores.refusal(admin, "PUT", path, settings + [library: "Archiv"]) == [HttpStatus.CONFLICT, "fileStore.usedBySource"]
        stores.refusal(admin, "PUT", path, settings + [basePath: "/alt"]) == [HttpStatus.CONFLICT, "fileStore.usedBySource"]

        and: "another app reads the same files - with its own secret"
        api.status(admin, "PUT", path, settings + [directoryId: "99999999-9999-9999-9999-999999999999",
                clientId: "88888888-8888-8888-8888-888888888888", password: "other-app-secret"]) == HttpStatus.OK
    }

    def "a scheduled task's read_files step reads a library's folder"() {
        given:
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, tasksAllowed: true])
        def model = api.chatModel()
        api.allow(tenant.id, model)
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/${model.provider}", [apiKey: "own-key-of-${model.provider}".toString()])
        checkedStore(tenant, sharePoint() + [name: "Bibliothek"])
        def tasks = new TaskApi(api)
        TaskApi.mailbox()
        def task = tasks.create(admin, """\
            version: 1
            name: Library check
            notify: [ops@example.com]
            steps:
              - id: logs
                type: read_files
                store: Bibliothek
                pattern: "*_interface.log"
              - id: check
                type: agent
                model: ${model.displayName}
                input: [logs]
                checks:
                  - name: Interface
                    severity: error
                    instruction: Were there timeouts to interface X? [[find timed out]]
            """.stripIndent())

        when:
        def run = tasks.awaitRun(admin, task.id, tasks.start(admin, task.id))

        then:
        (run.run as Map).state == "DONE"
        def read = (run.steps as List<Map>).find { it.stepId == "logs" }.result as Map
        read.files == 1
        read.lines == 1
    }

    private static Map sharePoint() {
        [name    : "SharePoint " + UUID.randomUUID(), type: "SHAREPOINT", directoryId: FakeMicrosoft.DIRECTORY,
         clientId: FakeMicrosoft.CLIENT, password: FakeMicrosoft.SECRET, siteUrl: FakeMicrosoft.SITE,
         basePath: "/logs"]
    }

    /** A store whose first check succeeded - readable for tasks, sources and the folder chooser. */
    private Map checkedStore(Map tenant, Map settings) {
        def admin = api.tenantAdminToken(tenant.id)
        def store = stores.create(admin, settings)
        stores.check(admin, store.id).store as Map
    }
}
