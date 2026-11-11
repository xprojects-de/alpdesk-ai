package de.alpdesk.controller.filestore

import de.alpdesk.entities.filestore.FileStore
import de.alpdesk.repositories.filestore.FileStoreRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.filestore.FileStoreApi
import de.alpdesk.services.filestore.FileStoreService
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant

import static de.alpdesk.services.filestore.FileStoreApi.STORES
import static de.alpdesk.services.filestore.FileStoreApi.closedPort

/**
 * The tenant's file stores (docs/architecture/file-stores.md) without a server: adding, changing and removing one
 * connect nowhere - the rules of the fields, unique names, another server needing the password again, the password
 * encrypted and never sent back, another tenant's stores answering 404 - and an S3 store's endpoint, region, bucket and
 * access key. What needs a real SFTP, FTPS or S3 server is in FileStoreServerSpec.
 */
@MicronautTest(transactional = false)
class FileStoreSpec extends Specification {

    static final String FINGERPRINT = "SHA256:" + "A" * 43

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FileStoreRepository repository

    @Inject
    FileStoreService service

    @Shared
    AiApi api

    @Shared
    FileStoreApi stores

    def setup() {
        api = api ?: new AiApi(client)
        stores = stores ?: new FileStoreApi(api, client)
    }

    def "a store is saved without connecting - its password never comes back"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)

        when:
        def store = stores.create(admin, sftp())

        then:
        store.type == "SFTP"
        store.host == "localhost"
        store.port == 2222
        store.username == "check"
        store.basePath == "/logs"
        store.fingerprint == null
        store.checkedAt == null
        !store.containsKey("password")
        !store.containsKey("secret")

        and: "the admin sees the same store"
        (api.admin("GET", "/admin/tenants/${tenant.id}/file-stores") as List<Map>)*.id == [store.id]
    }

    def "invalid input is refused with its reason: #field #value"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        expect:
        stores.refusal(admin, "POST", STORES, sftp() + [(field): value]) == [HttpStatus.BAD_REQUEST, code]

        where:
        field      | value                 | code
        "name"     | null                  | "fileStore.nameRequired"
        "name"     | " "                   | "fileStore.nameRequired"
        "name"     | "x" * 81              | "fileStore.nameTooLong"
        "type"     | "FTP"                 | "fileStore.typeUnknown"
        "type"     | null                  | "fileStore.typeUnknown"
        "host"     | null                  | "fileStore.hostRequired"
        "host"     | "sftp://example.com"  | "fileStore.hostInvalid"
        "host"     | "check@example.com"   | "fileStore.hostInvalid"
        "host"     | "example.com:22"      | "fileStore.hostInvalid"
        "host"     | "example.com/logs"    | "fileStore.hostInvalid"
        "host"     | "10.0.0.5"            | "fileStore.internalNetwork"
        "host"     | "169.254.169.254"     | "fileStore.internalNetwork"
        "host"     | "nowhere.invalid"     | "fileStore.unknownHost"
        "port"     | 0                     | "fileStore.portInvalid"
        "port"     | 65536                 | "fileStore.portInvalid"
        "username" | null                  | "fileStore.usernameRequired"
        "username" | "check\nroot"         | "fileStore.usernameInvalid"
        "password" | null                  | "fileStore.passwordRequired"
        "password" | ""                    | "fileStore.passwordRequired"
        "password" | "secret\r\nQUIT"      | "fileStore.passwordInvalid"
        "basePath" | "logs"                | "fileStore.pathInvalid"
        "basePath" | "/home/../etc"        | "fileStore.pathInvalid"
        "basePath" | "/home/./check"       | "fileStore.pathInvalid"
        "basePath" | "\\home\\check"       | "fileStore.pathInvalid"
    }

    def "port and folder have defaults, and the folder is kept in one form"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        expect:
        with(stores.create(admin, sftp() + [type: type, port: null, basePath: basePath])) {
            it.port == port
            it.basePath == stored
        }

        where:
        type   | basePath             | port | stored
        "SFTP" | null                 | 22   | "/"
        "sftp" | "  "                 | 22   | "/"
        "FTPS" | "/home//check/logs/" | 21   | "/home/check/logs"
    }

    def "a name is unique within the tenant, whatever the case - another tenant may use it"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def other = api.tenantAdminToken(api.tenant().id)
        stores.create(admin, sftp() + [name: "Protokolle"])

        expect:
        stores.refusal(admin, "POST", STORES, sftp() + [name: "PROTOKOLLE"]) == [HttpStatus.CONFLICT, "fileStore.nameTaken"]
        stores.create(other, sftp() + [name: "Protokolle"]).name == "Protokolle"
    }

    def "a change on the same server keeps password and fingerprint - another server needs the password again"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, sftp())
        stores.confirm(admin, store.id, FINGERPRINT)
        def path = "${STORES}/${store.id}"
        def secret = repository.findById(store.id as Long).get().secret()

        when: "renamed, the password left out"
        def renamed = api.call(admin, "PUT", path, sftp() + [name: "Umbenannt", password: null]) as Map

        then:
        renamed.name == "Umbenannt"
        renamed.fingerprint == FINGERPRINT
        repository.findById(store.id as Long).get().secret() == secret

        and: "another port or type without a password is refused - the stored one does not go to another server"
        stores.refusal(admin, "PUT", path, sftp() + [port: 2223, password: null]) == [HttpStatus.BAD_REQUEST, "fileStore.passwordRequired"]
        stores.refusal(admin, "PUT", path, sftp() + [type: "FTPS", password: null]) == [HttpStatus.BAD_REQUEST, "fileStore.passwordRequired"]

        when: "another server with its password"
        def moved = api.call(admin, "PUT", path, sftp() + [port: 2223]) as Map

        then: "its fingerprint is to be confirmed anew"
        moved.fingerprint == null
        moved.port == 2223
    }

    def "a fingerprint must look like one"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, sftp())

        expect:
        stores.refusal(admin, "PUT", "${STORES}/${store.id}/fingerprint", [fingerprint: value]) ==
                [HttpStatus.BAD_REQUEST, "fileStore.fingerprintInvalid"]

        where:
        value << [null, "", "MD5:ab:cd", "SHA256:short"]
    }

    def "the password is stored encrypted and never sent back"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, sftp())

        expect:
        def stored = repository.findById(store.id as Long).get()
        stored.secret().startsWith("enc:v1:")
        !stored.secret().contains("check-secret")
        !(api.call(admin, "GET", STORES) as List<Map>).any { it.containsKey("password") || it.containsKey("secret") }
    }

    def "a server out of reach fails the check - its answer, not an error"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, sftp() + [port: closedPort()])

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "FAILED"
        result.errorCode == "fileStore.unreachable"
        result.errorParams == [host: "localhost"]
        (result.store as Map).checkError == "fileStore.unreachable"
    }

    def "a host leading into the internal network since it was saved is refused before every connection"() {
        given: "a store saved while its host was allowed - now its address is an internal one not on the list"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = stores.create(admin, sftp())
        def saved = repository.findById(store.id as Long).get()
        repository.update(new FileStore(saved.id(), saved.tenantId(), saved.name(), saved.type(),
                saved.settings().replace('"localhost"', '"127.0.0.3"'), saved.secret(), null, null, false,
                saved.createdAt(), saved.updatedAt()))

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "FAILED"
        result.errorCode == "fileStore.internalNetwork"

        when: "a task's run opens it"
        service.open(tenant.id as Long, store.id as Long)

        then:
        def e = thrown(ServiceException)
        e.code() == "fileStore.internalNetwork"
    }

    def "a check's outcome is not written to a store changed while it ran"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, sftp())
        def checkedAsItWas = repository.findById(store.id as Long).get()
        api.call(admin, "PUT", "${STORES}/${store.id}", sftp() + [name: "Umbenannt", password: null])

        when: "the check that started before the change ends"
        def written = repository.updateCheck(checkedAsItWas.id(), checkedAsItWas.updatedAt(), Instant.now(), "fileStore.failed")

        then:
        written == 0
        repository.findById(store.id as Long).get().checkError() == null
    }

    def "another tenant's stores answer 404 and are not listed"() {
        given:
        def owner = api.tenant()
        def store = stores.create(api.tenantAdminToken(owner.id), sftp())
        def stranger = api.tenant()
        def other = api.tenantAdminToken(stranger.id)
        def path = "${STORES}/${store.id}"

        expect:
        api.call(other, "GET", STORES) == []
        api.status(other, "PUT", path, sftp()) == HttpStatus.NOT_FOUND
        api.status(other, "POST", "${path}/check") == HttpStatus.NOT_FOUND
        api.status(other, "PUT", "${path}/fingerprint", [fingerprint: FINGERPRINT]) == HttpStatus.NOT_FOUND
        api.status(other, "DELETE", path) == HttpStatus.NOT_FOUND

        and: "nor through the admin API under another tenant's id"
        api.status(api.adminToken, "POST", "/admin/tenants/${stranger.id}/file-stores/${store.id}/check") == HttpStatus.NOT_FOUND
        api.status(api.adminToken, "DELETE", "/admin/tenants/${stranger.id}/file-stores/${store.id}") == HttpStatus.NOT_FOUND
    }

    def "the admin keeps a tenant's stores like its tenant admins; an unknown tenant answers 404"() {
        given:
        def tenant = api.tenant()
        def base = "/admin/tenants/${tenant.id}/file-stores"

        when:
        def store = api.admin("POST", base, sftp()) as Map
        def confirmed = api.admin("PUT", "${base}/${store.id}/fingerprint", [fingerprint: FINGERPRINT]) as Map

        then:
        confirmed.fingerprint == FINGERPRINT
        (api.call(api.tenantAdminToken(tenant.id), "GET", STORES) as List<Map>)*.id == [store.id]
        api.status(api.adminToken, "GET", "/admin/tenants/987654321/file-stores") == HttpStatus.NOT_FOUND
    }

    def "tasks may write into a store only once it is switched on - off for a new store, a change without it keeps it"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        when:
        def plain = stores.create(admin, sftp())
        def writing = stores.create(admin, sftp() + [taskWrite: true])
        def renamed = api.call(admin, "PUT", "${STORES}/${writing.id}", sftp() + [name: "Umbenannt", password: null]) as Map
        def off = api.call(admin, "PUT", "${STORES}/${writing.id}", sftp() + [name: "Umbenannt", password: null,
                                                                               taskWrite: false]) as Map

        then:
        plain.taskWrite == false
        writing.taskWrite == true
        renamed.taskWrite == true
        off.taskWrite == false
        !service.writable(repository.findById(writing.id as Long).get())
    }

    def "the switch alone does not let a task write - the store must be ready to read (checked, confirmed) too"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def store = stores.create(admin, sftp() + [taskWrite: true])

        expect:
        !service.writable(repository.findById(store.id as Long).get())

        when:
        api.call(admin, "PUT", "${STORES}/${store.id}/fingerprint", [fingerprint: FINGERPRINT])

        then:
        service.writable(repository.findById(store.id as Long).get())
    }

    def "a deleted store is gone"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, sftp())

        expect:
        api.status(admin, "DELETE", "${STORES}/${store.id}") == HttpStatus.NO_CONTENT
        api.call(admin, "GET", STORES) == []
        api.status(admin, "POST", "${STORES}/${store.id}/check") == HttpStatus.NOT_FOUND
    }

    def "an S3 store is saved without connecting - its endpoint in one form, its secret never comes back"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        when:
        def store = stores.create(admin, s3() + [endpoint: " HTTPS://LocalHost:9000/ ", region: null])

        then:
        store.type == "S3"
        store.endpoint == "https://localhost:9000"
        store.region == "us-east-1"
        store.bucket == "check"
        store.accessKeyId == "CHECKACCESSKEY"
        store.basePath == "/logs"
        store.host == null
        store.port == null
        store.username == null
        store.fingerprint == null
        !store.containsKey("password")
        !repository.findById(store.id as Long).get().secret().contains("check-secret-key")
    }

    def "Amazon S3 needs no endpoint but its region: #region"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        expect:
        with(stores.create(admin, s3() + [endpoint: endpoint, region: region])) {
            it.endpoint == null
            it.region == stored
        }

        where:
        endpoint | region         | stored
        null     | "eu-central-1" | "eu-central-1"
        " "      | "EU-West-3"    | "eu-west-3"
    }

    def "an S3 store's invalid input is refused with its reason: #field #value"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        expect:
        stores.refusal(admin, "POST", STORES, s3() + [(field): value]) == [HttpStatus.BAD_REQUEST, code]

        where:
        field         | value                          | code
        "endpoint"    | "ftp://localhost:9000"         | "fileStore.endpointInvalid"
        "endpoint"    | "localhost:9000"               | "fileStore.endpointInvalid"
        "endpoint"    | "https://key@localhost:9000"   | "fileStore.endpointInvalid"
        "endpoint"    | "https://localhost:9000/check" | "fileStore.endpointInvalid"
        "endpoint"    | "https://localhost:9000/?a=1"  | "fileStore.endpointInvalid"
        "endpoint"    | "https://local_host"           | "fileStore.endpointInvalid"
        "endpoint"    | "http://example.com"           | "fileStore.plainHttp"
        "endpoint"    | "https://10.0.0.5"             | "fileStore.internalNetwork"
        "endpoint"    | "https://169.254.169.254"      | "fileStore.internalNetwork"
        "endpoint"    | "https://nowhere.invalid"      | "fileStore.unknownHost"
        "region"      | "eu central 1"                 | "fileStore.regionInvalid"
        "bucket"      | null                           | "fileStore.bucketRequired"
        "bucket"      | "ab"                           | "fileStore.bucketInvalid"
        "bucket"      | "Check"                        | "fileStore.bucketInvalid"
        "bucket"      | "check/logs"                   | "fileStore.bucketInvalid"
        "accessKeyId" | null                           | "fileStore.accessKeyRequired"
        "accessKeyId" | "CHECK KEY"                    | "fileStore.accessKeyInvalid"
        "password"    | null                           | "fileStore.passwordRequired"
        "basePath"    | "logs"                         | "fileStore.pathInvalid"
        "basePath"    | "/logs/../.."                  | "fileStore.pathInvalid"
    }

    def "Amazon S3 without a region is refused"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        expect:
        stores.refusal(admin, "POST", STORES, s3() + [endpoint: null, region: null]) ==
                [HttpStatus.BAD_REQUEST, "fileStore.regionRequired"]
    }

    def "plain http goes only to a host in the internal hosts"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        expect:
        stores.create(admin, s3() + [endpoint: "http://localhost:9000"]).endpoint == "http://localhost:9000"
        stores.refusal(admin, "POST", STORES, s3() + [endpoint: "http://example.com"]) ==
                [HttpStatus.BAD_REQUEST, "fileStore.plainHttp"]
    }

    def "another folder or name keeps an S3 store's secret - another bucket, endpoint, region or key needs it again: #field"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, s3())
        def path = "${STORES}/${store.id}"
        def secret = repository.findById(store.id as Long).get().secret()

        when: "another folder and name without the secret"
        api.call(admin, "PUT", path, s3() + [name: "Umbenannt", basePath: "/archiv", password: null])

        then:
        repository.findById(store.id as Long).get().secret() == secret

        and:
        stores.refusal(admin, "PUT", path, s3() + [(field): value, password: null]) ==
                [HttpStatus.BAD_REQUEST, "fileStore.passwordRequired"]

        where:
        field         | value
        "bucket"      | "other"
        "endpoint"    | "https://127.0.0.1:9000"
        "region"      | "eu-west-1"
        "accessKeyId" | "OTHERKEY"
    }

    def "an SFTP store turned into an S3 store needs the secret"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, sftp())

        expect:
        stores.refusal(admin, "PUT", "${STORES}/${store.id}", s3() + [password: null]) ==
                [HttpStatus.BAD_REQUEST, "fileStore.passwordRequired"]
        (api.call(admin, "PUT", "${STORES}/${store.id}", s3()) as Map).bucket == "check"
    }

    def "an S3 store out of reach fails the check - and has no fingerprint to confirm"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, s3() + [endpoint: "http://localhost:${closedPort()}".toString()])

        when:
        def result = stores.check(admin, store.id)

        then:
        result.status == "FAILED"
        result.errorCode == "fileStore.unreachable"
        result.errorParams == [host: "localhost"]

        and:
        stores.refusal(admin, "PUT", "${STORES}/${store.id}/fingerprint", [fingerprint: FINGERPRINT]) ==
                [HttpStatus.CONFLICT, "fileStore.noFingerprint"]
    }

    def "a SharePoint store is saved without connecting - its site's address in one form, its secret never comes back"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        when:
        def store = stores.create(admin, sharePoint() + [directoryId: DIRECTORY.toUpperCase(),
                siteUrl: " HTTPS://MusterFirma.SharePoint.com//sites/projekte/ ", library: " Dokumente "])

        then:
        store.type == "SHAREPOINT"
        store.directoryId == DIRECTORY
        store.clientId == CLIENT
        store.siteUrl == "https://musterfirma.sharepoint.com/sites/projekte"
        store.library == "Dokumente"
        store.basePath == "/logs"
        store.host == null
        store.fingerprint == null
        !store.containsKey("password")
        !repository.findById(store.id as Long).get().secret().contains("app-secret-value")

        and: "a OneDrive's address, a site without a path and no library"
        stores.create(admin, sharePoint() + [siteUrl: "https://musterfirma-my.sharepoint.com/personal/anna_musterfirma_example"])
                .siteUrl == "https://musterfirma-my.sharepoint.com/personal/anna_musterfirma_example"
        with(stores.create(admin, sharePoint() + [siteUrl: "https://musterfirma.sharepoint.com", library: " "])) {
            it.siteUrl == "https://musterfirma.sharepoint.com"
            it.library == null
        }
    }

    def "a SharePoint store's invalid input is refused with its reason: #field #value"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)

        expect:
        stores.refusal(admin, "POST", STORES, sharePoint() + [(field): value]) == [HttpStatus.BAD_REQUEST, code]

        where:
        field         | value                                                  | code
        "directoryId" | null                                                   | "fileStore.directoryIdRequired"
        "directoryId" | "musterfirma.onmicrosoft.com"                          | "fileStore.directoryIdInvalid"
        "clientId"    | null                                                   | "fileStore.clientIdRequired"
        "clientId"    | "1111-2222"                                            | "fileStore.clientIdInvalid"
        "siteUrl"     | null                                                   | "fileStore.siteUrlRequired"
        "siteUrl"     | "http://musterfirma.sharepoint.com/sites/projekte"     | "fileStore.siteUrlInvalid"
        "siteUrl"     | "https://sharepoint.example.com/sites/projekte"        | "fileStore.siteUrlInvalid"
        "siteUrl"     | "https://musterfirma.sharepoint.com.example.com/sites" | "fileStore.siteUrlInvalid"
        "siteUrl"     | "https://musterfirma.sharepoint.com:8443/sites/x"      | "fileStore.siteUrlInvalid"
        "siteUrl"     | "https://musterfirma.sharepoint.com/sites/x?web=1"     | "fileStore.siteUrlInvalid"
        "siteUrl"     | "https://user@musterfirma.sharepoint.com/sites/x"      | "fileStore.siteUrlInvalid"
        "siteUrl"     | "https://musterfirma.sharepoint.com/sites/../x"        | "fileStore.siteUrlInvalid"
        "siteUrl"     | "musterfirma.sharepoint.com/sites/projekte"            | "fileStore.siteUrlInvalid"
        "library"     | "Dokumente/Unterordner"                                | "fileStore.libraryInvalid"
        "library"     | "x" * 256                                              | "fileStore.libraryInvalid"
        "password"    | null                                                   | "fileStore.passwordRequired"
        "basePath"    | "logs"                                                 | "fileStore.pathInvalid"
    }

    def "another site, library or folder keeps a SharePoint store's secret - another directory or app needs it again: #field"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, sharePoint())
        def path = "${STORES}/${store.id}"
        def secret = repository.findById(store.id as Long).get().secret()

        when: "another site, library and folder without the secret - the secret goes to Microsoft's login only"
        api.call(admin, "PUT", path, sharePoint() + [siteUrl: "https://musterfirma.sharepoint.com/sites/archiv",
                library: "Archiv", basePath: "/alt", password: null])

        then:
        repository.findById(store.id as Long).get().secret() == secret

        and:
        stores.refusal(admin, "PUT", path, sharePoint() + [(field): value, password: null]) ==
                [HttpStatus.BAD_REQUEST, "fileStore.passwordRequired"]

        where:
        field         | value
        "directoryId" | "99999999-9999-9999-9999-999999999999"
        "clientId"    | "99999999-9999-9999-9999-999999999999"
    }

    def "an S3 store turned into a SharePoint store needs the secret"() {
        given:
        def admin = api.tenantAdminToken(api.tenant().id)
        def store = stores.create(admin, s3())

        expect:
        stores.refusal(admin, "PUT", "${STORES}/${store.id}", sharePoint() + [password: null]) ==
                [HttpStatus.BAD_REQUEST, "fileStore.passwordRequired"]
    }

    /** A store on localhost (an internal host the test properties allow) - nothing is connected to here. */
    private static Map sftp() {
        [name    : "SFTP " + UUID.randomUUID(), type: "SFTP", host: "localhost", port: 2222, username: "check",
         password: "check-secret", basePath: "/logs"]
    }

    static final String DIRECTORY = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    static final String CLIENT = "11111111-2222-3333-4444-555555555555"

    /** A SharePoint store - nothing is connected to here. */
    private static Map sharePoint() {
        [name    : "SharePoint " + UUID.randomUUID(), type: "SHAREPOINT", directoryId: DIRECTORY, clientId: CLIENT,
         password: "app-secret-value", siteUrl: "https://musterfirma.sharepoint.com/sites/projekte", basePath: "/logs"]
    }

    /** An S3 store on localhost - nothing is connected to here. */
    private static Map s3() {
        [name       : "S3 " + UUID.randomUUID(), type: "S3", endpoint: "https://localhost:9000", region: "eu-central-1",
         bucket     : "check", accessKeyId: "CHECKACCESSKEY", password: "check-secret-key", basePath: "/logs"]
    }
}
