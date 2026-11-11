package de.alpdesk.controller.filestore

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.document.SourceApi
import de.alpdesk.services.filestore.FileStoreApi
import de.alpdesk.services.filestore.FileStoreService
import de.alpdesk.services.filestore.FileStoreServers
import de.alpdesk.services.filestore.FtpsStoreClient
import de.alpdesk.services.filestore.ServerSettings
import de.alpdesk.services.task.TaskApi
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

import static de.alpdesk.services.filestore.FileStoreApi.STORES

/**
 * The file stores against real servers (docs/architecture/file-stores.md): a check shows the server's key or
 * certificate first; only once a tenant admin confirmed its fingerprint does the password go out and the folder get
 * read - another key or certificate later gets no login. FTPS data connections resume the TLS session the server
 * requires. An S3 store has no fingerprint: its first check reads the folder. What needs no server is in FileStoreSpec.
 * <p>
 * Runs only with FILE_STORE_TEST set (decided 13 October 2026, for S3 on 29 October 2026): its SFTP and FTPS servers
 * are built from Dockerfiles, so neither GitHub nor a plain local run builds them - run it by hand with every change of services.filestore and
 * before a release: FILE_STORE_TEST=1 ./gradlew test --tests "de.alpdesk.controller.filestore.FileStoreServerSpec"
 */
@Requires({ env.FILE_STORE_TEST })
@MicronautTest(transactional = false)
// A task needs a mail server to report to - the GreenMail of TaskApi
@Property(name = "app.mail.host", value = "127.0.0.1")
@Property(name = "app.mail.port", value = "39525")
@Property(name = "app.mail.security", value = "NONE")
@Property(name = "app.mail.from", value = "tasks@example.com")
class FileStoreServerSpec extends Specification {

    static final String SFTP_FOLDER = "/home/check/logs"
    static final String FTPS_FOLDER = "/logs"
    static final String S3_FOLDER = "/logs"
    static final String OTHER_FINGERPRINT = "SHA256:" + "A" * 43

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FileStoreService service

    @Inject
    FtpsStoreClient ftpsClient

    @Shared
    AiApi api

    @Shared
    FileStoreApi stores

    def setup() {
        api = api ?: new AiApi(client)
        stores = stores ?: new FileStoreApi(api, client)
    }

    def "an SFTP store shows the server's key first - the password goes out only after it was confirmed"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = stores.create(admin, sftp())

        when: "the first check meets the server but does not log in"
        def first = stores.check(admin, store.id)

        then:
        first.status == "UNCONFIRMED"
        first.fingerprint ==~ /SHA256:[A-Za-z0-9+\/]{43}/
        first.entries == []
        first.errorCode == "fileStore.fingerprintUnconfirmed"

        when: "a tenant admin confirms the fingerprint shown"
        def confirmed = stores.confirm(admin, store.id, first.fingerprint as String)
        def second = stores.check(admin, store.id)

        then:
        confirmed.fingerprint == first.fingerprint
        second.status == "OK"
        second.entries == [FileStoreServers.LOG_FILE]
        second.errorCode == null
        (second.store as Map).checkedAt != null
        (second.store as Map).checkError == null

        and: "the admin sees the same store"
        (api.admin("GET", "/admin/tenants/${tenant.id}/file-stores") as List<Map>)*.fingerprint == [first.fingerprint]
    }

    def "an FTPS store with a certificate of its own works the same - its data connections resume the TLS session"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, ftps())

        when:
        def first = stores.check(admin, store.id)

        then: "the certificate is not one an authority vouches for - it must be confirmed"
        first.status == "UNCONFIRMED"
        first.fingerprint ==~ /SHA256:[A-Za-z0-9+\/]{43}/

        when:
        stores.confirm(admin, store.id, first.fingerprint as String)
        def second = stores.check(admin, store.id)

        then: "the server lists the folder only over a data connection that resumed the control connection's session"
        second.status == "OK"
        second.entries == [FileStoreServers.LOG_FILE]
    }

    def "a server presenting another key or certificate than the one confirmed gets no login: #type"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, settings)
        def shown = stores.check(admin, store.id).fingerprint
        stores.confirm(admin, store.id, OTHER_FINGERPRINT)

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "CHANGED"
        result.fingerprint == shown
        result.entries == []
        result.errorCode == "fileStore.fingerprintChanged"
        (result.store as Map).checkError == "fileStore.fingerprintChanged"

        where:
        type   | settings
        "SFTP" | sftp()
        "FTPS" | ftps()
    }

    def "a check that fails says why - and the store keeps it: #reason"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = confirmedStore(admin, settings)

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "FAILED"
        result.errorCode == code
        (result.store as Map).checkError == code

        where:
        reason                 | settings                                   | code
        "SFTP, wrong password" | sftp() + [password: "wrong-secret"]        | "fileStore.loginFailed"
        "SFTP, no such folder" | sftp() + [basePath: "/home/check/missing"] | "fileStore.folderNotFound"
        "FTPS, wrong password" | ftps() + [password: "wrong-secret"]        | "fileStore.loginFailed"
        "FTPS, no such folder" | ftps() + [basePath: "/missing"]            | "fileStore.folderNotFound"
        "S3, wrong secret"     | s3() + [password: "wrong-secret"]          | "fileStore.accessKeyRefused"
        "S3, unknown key"      | s3() + [accessKeyId: "UNKNOWNKEY"]         | "fileStore.accessKeyRefused"
        "S3, no such bucket"   | s3() + [bucket: "missing"]                 | "fileStore.bucketNotFound"
        "S3, no such folder"   | s3() + [basePath: "/missing"]              | "fileStore.folderNotFound"
    }

    def "an empty folder is no error: #type"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = confirmedStore(admin, settings)

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "OK"
        result.entries == []

        where:
        type   | settings
        "SFTP" | sftp() + [basePath: "/home/check/empty"]
        "FTPS" | ftps() + [basePath: "/empty"]
        "S3, a folder a console created"  | s3() + [basePath: "/empty"]
        "S3, an empty bucket"             | s3() + [bucket: FileStoreServers.S3_EMPTY_BUCKET, basePath: "/"]
    }

    def "a file name with an umlaut comes as written - a pattern finds it: #type"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = confirmedStore(admin, settings)

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "OK"
        result.entries == [FileStoreServers.UMLAUT_FILE]

        where:
        type   | settings
        "SFTP" | sftp() + [basePath: "/home/check/names"]
        "FTPS" | ftps() + [basePath: "/names"]
        "S3"   | s3() + [basePath: "/names"]
    }

    def "renaming keeps the confirmed server - the next check logs in without the password being entered again"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = confirmedStore(admin, sftp())

        when:
        api.call(admin, "PUT", "${STORES}/${store.id}", sftp() + [name: "Umbenannt", password: null])

        then:
        stores.check(admin, store.id).status == "OK"
    }

    def "the admin checks a tenant's store like its tenant admins"() {
        given:
        def base = "/admin/tenants/${api.tenant().id}/file-stores"
        def store = api.admin("POST", base, sftp()) as Map
        def first = api.admin("POST", "${base}/${store.id}/check") as Map

        when:
        api.admin("PUT", "${base}/${store.id}/fingerprint", [fingerprint: first.fingerprint])

        then:
        (api.admin("POST", "${base}/${store.id}/check") as Map).status == "OK"
    }

    def "a task reads a store's files: the folder's files with size and time, a file from any offset - #type"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = confirmedStore(admin, settings)
        String line = "2026-10-13 08:00:05 ERROR connection to interface X timed out\n"

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        def files = session.list(folder)
        def whole = new String(session.read(folder + "/" + FileStoreServers.LOG_FILE, 0, 10_000), "UTF-8")
        def rest = new String(session.read(folder + "/" + FileStoreServers.LOG_FILE, 20, 5), "UTF-8")
        def beyond = session.read(folder + "/" + FileStoreServers.LOG_FILE, 10_000, 5)
        session.close()

        then:
        files*.name() == [FileStoreServers.LOG_FILE]
        files[0].size() == line.length()
        files[0].modified() != null
        whole == line
        rest == line.substring(20, 25)
        beyond.length == 0

        where:
        type   | settings | folder
        "SFTP" | sftp()   | SFTP_FOLDER
        "FTPS" | ftps()   | FTPS_FOLDER
        "S3"   | s3()     | S3_FOLDER
    }

    def "a task's write creates a file, replaces it whole and leaves no temporary file behind - #type"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = confirmedStore(admin, settings)
        String name = "rows-" + UUID.randomUUID() + ".csv"

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        session.write(folder + "/" + name, "date;gross\n2026-01-24;119,00\n".getBytes("UTF-8"))
        session.write(folder + "/" + name, "date;gross\n2026-01-24;119,00\n2026-01-25;20,00\n".getBytes("UTF-8"))
        // a second file right after: a task writing one file per year reads the next one on the same session
        def other = folder + "/" + name + ".other"
        session.write(other, "x\n".getBytes("UTF-8"))
        def otherRead = new String(session.read(other, 0, 100), "UTF-8")
        def read = new String(session.read(folder + "/" + name, 0, 10_000), "UTF-8")
        def names = session.list(folder)*.name()
        session.close()

        then:
        read == "date;gross\n2026-01-24;119,00\n2026-01-25;20,00\n"
        otherRead == "x\n"
        names.findAll { it.startsWith(name) } == [name, name + ".other"]

        where:
        type   | settings | folder
        "SFTP" | sftp()   | "/home/check/export"
        "FTPS" | ftps()   | "/export"
        "S3"   | s3()     | "/export"
    }

    def "a write into a folder that does not exist fails with fileStore.writeFailed - nothing is made: #type"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = confirmedStore(admin, settings)
        def session = service.open(tenant.id as Long, store.id as Long)

        when:
        session.write(folder + "/missing/rows.csv", "a\n".getBytes("UTF-8"))

        then:
        def e = thrown(ServiceException)
        e.code() == "fileStore.writeFailed"

        cleanup:
        session?.close()

        where:
        type   | settings | folder
        "SFTP" | sftp()   | "/home/check/export"
        "FTPS" | ftps()   | "/export"
    }

    def "a file read only partly - larger than the step's limit, or grown since it was listed - leaves the session usable: #type"() {
        given: "a large log (about 9 MB) and the small one, in one session"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = confirmedStore(admin, settings)
        String large = root + "/large/large.log"
        String line = "2026-10-13 08:00:05 ERROR connection to interface X timed out\n"

        when: "the large one is read only in part - twice, from its start and from the middle - then the small one"
        def session = service.open(tenant.id as Long, store.id as Long)
        def start = new String(session.read(large, 0, 1_000_000), "UTF-8")
        def middle = new String(session.read(large, 4_000_000, 100), "UTF-8")
        def small = new String(session.read(root + "/logs/" + FileStoreServers.LOG_FILE, 0, 10_000), "UTF-8")
        session.close()

        then:
        start.length() == 1_000_000
        start.startsWith("2026-10-13 08:00:06 INFO line 1 of a large log\n")
        middle.length() == 100
        small == line

        where:
        type   | settings | root
        "SFTP" | sftp()   | "/home/check"
        "FTPS" | ftps()   | ""
        "S3"   | s3()     | ""
    }

    def "a folder's subfolders are listed as folders - not the folder itself or its parent: #type"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = confirmedStore(admin, settings)

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        def entries = session.list(root.isEmpty() ? "/" : root)
        session.close()

        then:
        entries.findAll { it.folder() }*.name().containsAll(["large", "logs"])
        entries.every { !(it.name() in [".", ".."]) }
        entries.findAll { it.folder() }.every { it.size() == 0 }

        where:
        type   | settings | root
        "SFTP" | sftp()   | "/home/check"
        "FTPS" | ftps()   | ""
        "S3"   | s3()     | ""
    }

    def "choosing a folder lists the real server's subfolders over HTTP: #type"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = confirmedStore(admin, settings)

        when:
        def listing = api.call(admin, "GET", "/tenant/admin/file-stores/${store.id}/folders") as Map

        then:
        (listing.folders as List).containsAll(["empty", "large", "logs", "names"])
        !(listing.folders as List).any { it.startsWith(".") }

        where:
        type   | settings
        "SFTP" | sftp() + [basePath: "/home/check"]
        "FTPS" | ftps() + [basePath: "/"]
        "S3"   | s3() + [basePath: "/"]
    }

    def "an S3 store has no fingerprint: its first check reads the folder, and it is readable from then on"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = stores.create(admin, s3())

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "OK"
        result.fingerprint == null
        result.entries == [FileStoreServers.LOG_FILE]
        result.errorCode == null
        (result.store as Map).checkError == null
        service.readable(service.findByName(tenant.id as Long, store.name as String).get())

        and: "there is no fingerprint to confirm"
        stores.refusal(admin, "PUT", "${STORES}/${store.id}/fingerprint", [fingerprint: OTHER_FINGERPRINT]) ==
                [HttpStatus.CONFLICT, "fileStore.noFingerprint"]
    }

    def "an FTPS folder that may be entered but not listed is refused, never taken for an empty one"() {
        given:
        def tenant = api.tenant()
        def store = confirmedStore(api.tenantAdminToken(tenant.id), ftps())

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        def empty = session.list("/empty")
        session.list("/locked")

        then: "a knowledge source on it would otherwise delete the documents of every file below it"
        def e = thrown(ServiceException)
        e.code() == "fileStore.folderNotFound"
        empty == []

        cleanup:
        session?.close()
    }

    def "an S3 folder with more files than one listing page holds is listed whole - a folder's own object is no file"() {
        given:
        def tenant = api.tenant()
        def store = stores.create(api.tenantAdminToken(tenant.id), s3())

        when:
        def session = service.open(tenant.id as Long, store.id as Long)
        def many = session.list("/many")
        def empty = session.list("/empty")
        session.close()

        then:
        many.size() == FileStoreServers.S3_MANY
        many.first().name() == "0001.log"
        many.last().name() == String.format("%04d.log", FileStoreServers.S3_MANY)
        many.every { !it.folder() && it.size() > 0 }
        empty == []
    }

    def "a knowledge source syncs a folder of an S3 bucket into the knowledge"() {
        given: "a tenant admin with an embedding model and a checked S3 store"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def store = stores.create(admin, s3() + [basePath: "/"])
        stores.check(admin, store.id)
        def sources = new SourceApi(api, null)

        when:
        def source = sources.source([token: admin, store: store], [folder: "logs"])
        sources.run(admin, source.id)
        def documents = sources.documents(admin, source.id)

        then:
        documents.keySet() == [FileStoreServers.LOG_FILE] as Set
        documents[FileStoreServers.LOG_FILE].status == "READY"
    }

    def "an S3 store a source reads takes a rotated access key - another bucket or folder is refused"() {
        given: "a source reading the store"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        def store = stores.create(admin, s3() + [basePath: "/"])
        stores.check(admin, store.id)
        new SourceApi(api, null).source([token: admin, store: store], [folder: "logs"])
        def path = "/tenant/admin/file-stores/${store.id}"
        def settings = s3() + [name: store.name, basePath: "/"]

        expect: "a rotated key reads the same files - saved with its secret"
        api.status(admin, "PUT", path, settings + [accessKeyId: "ROTATEDKEY", password: "rotated-secret"]) == HttpStatus.OK

        and: "another bucket or folder holds other files - the source's next listing would delete its documents"
        stores.refusal(admin, "PUT", path, settings + [bucket: "other", password: "x"]) == [HttpStatus.CONFLICT, "fileStore.usedBySource"]
        stores.refusal(admin, "PUT", path, settings + [basePath: "/alt", password: null]) == [HttpStatus.CONFLICT, "fileStore.usedBySource"]
    }

    def "a scheduled task's read_files step reads an S3 bucket's folder"() {
        given: "a tenant with tasks, a chat model and a checked S3 store"
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, tasksAllowed: true])
        def model = api.chatModel()
        api.allow(tenant.id, model)
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/${model.provider}", [apiKey: "own-key-of-${model.provider}".toString()])
        def store = stores.create(admin, s3() + [name: "Bucket"])
        stores.check(admin, store.id)
        def tasks = new TaskApi(api)
        TaskApi.mailbox()
        def task = tasks.create(admin, """\
            version: 1
            name: Bucket check
            notify: [ops@example.com]
            steps:
              - id: logs
                type: read_files
                store: Bucket
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

    def "a store whose fingerprint is not confirmed is not opened for reading - nothing is sent"() {
        given:
        def owner = api.tenant()
        def admin = api.tenantAdminToken(owner.id)
        def store = stores.create(admin, sftp())

        when:
        service.open(owner.id as Long, store.id as Long)

        then:
        def e = thrown(de.alpdesk.services.common.ServiceException)
        e.code() == "fileStore.fingerprintUnconfirmed"
    }

    def "an FTPS host answering with another address than the one allowed shows nothing - no fingerprint, no login"() {
        given: "the name leads to the server, the address allowed before connecting is another one"
        def allowed = InetAddress.getByName("127.0.0.2")
        def settings = new ServerSettings("localhost", FileStoreServers.ftpsPort(), FileStoreServers.USER, FTPS_FOLDER, null)

        when:
        ftpsClient.check(allowed, settings, FileStoreServers.PASSWORD)

        then:
        def e = thrown(de.alpdesk.services.common.ServiceException)
        e.code() == "fileStore.addressChanged"
    }

    private static Map sftp() {
        [name    : "SFTP " + UUID.randomUUID(), type: "SFTP", host: "localhost", port: FileStoreServers.sftpPort(),
         username: FileStoreServers.USER, password: FileStoreServers.PASSWORD, basePath: SFTP_FOLDER]
    }

    private static Map ftps() {
        [name    : "FTPS " + UUID.randomUUID(), type: "FTPS", host: "localhost", port: FileStoreServers.ftpsPort(),
         username: FileStoreServers.USER, password: FileStoreServers.PASSWORD, basePath: FTPS_FOLDER]
    }

    private static Map s3() {
        [name       : "S3 " + UUID.randomUUID(), type: "S3", endpoint: FileStoreServers.s3Endpoint(),
         bucket     : FileStoreServers.S3_BUCKET, accessKeyId: FileStoreServers.S3_ACCESS_KEY,
         password   : FileStoreServers.S3_SECRET, basePath: S3_FOLDER]
    }

    /** A store whose server's fingerprint was shown by a check and confirmed - an S3 store has none, it is checked. */
    private Map confirmedStore(String token, Map settings) {
        def store = stores.create(token, settings)
        if (settings.type == "S3") {
            return stores.check(token, store.id).store as Map
        }
        stores.confirm(token, store.id, stores.check(token, store.id).fingerprint as String)
    }
}
