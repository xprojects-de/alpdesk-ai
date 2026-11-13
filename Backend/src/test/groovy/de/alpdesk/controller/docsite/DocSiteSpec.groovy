package de.alpdesk.controller.docsite

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.docsite.DocSiteService
import de.alpdesk.services.docsite.DocSiteTokens
import de.alpdesk.storage.FileStorage
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.time.Instant

/**
 * The handbook (docs/architecture/doc-sites.md): a static website per scope - the whole tenant or a knowledge area -,
 * uploaded as a ZIP by the tenant admins and the admin, read by the users of that scope in an iframe, every file
 * served sandboxed through a short-lived address.
 */
@MicronautTest(transactional = false)
class DocSiteSpec extends Specification {

    static final byte[] INDEX = "<html><body><h1>Handbuch</h1><a href=\"anleitung/\">Anleitung</a></body></html>".bytes

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    DocSiteService service

    @Inject
    DocSiteTokens tokens

    @Inject
    FileStorage storage

    @Inject
    EmbeddedServer server

    @Shared
    AiApi api

    List<Object> tenants = []

    def setup() {
        api = api ?: new AiApi(client)
    }

    def cleanup() {
        tenants.each { api.status(api.adminToken, "DELETE", "/admin/tenants/${it}") }
    }

    def "a tenant admin uploads the tenant's website and a user reads it, sandboxed"() {
        given:
        def tenant = newTenant()
        def admin = api.tenantAdminToken(tenant.id)
        def user = api.userToken(tenant.id)

        when:
        def uploaded = upload(admin, ["index.html"          : INDEX,
                                      "style.css"           : "h1 { color: teal }".bytes,
                                      "anleitung/index.html": "<p>Schritt 1</p>".bytes,
                                      "Über uns.html"       : "<p>Musterfirma</p>".bytes,
                                      "Unser Team/index.html": "<p>Team</p>".bytes,
                                      "setup.exe"           : [1, 2, 3] as byte[]])

        then: "the website is stored without the file of another type"
        uploaded.site.areaId == null
        uploaded.site.fileCount == 5
        uploaded.skipped == [[path: "setup.exe", reason: "type not allowed"]]
        (api.call(user, "GET", "/tenant/doc-sites") as List)*.id == [uploaded.site.id]

        when:
        def address = addressFor(user, "/tenant/doc-sites/${uploaded.site.id}/address")
        def start = read(address)

        then: "its index.html, sandboxed, framed only by the app, without referrer"
        start.status == HttpStatus.OK
        start.body() == new String(INDEX)
        start.contentType.get().toString().startsWith("text/html")
        start.header("Content-Security-Policy").startsWith("sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox; frame-ancestors 'self'")
        start.header("X-Frame-Options") == "SAMEORIGIN"
        start.header("Referrer-Policy") == "no-referrer"
        start.header("X-Content-Type-Options") == "nosniff"
        start.header("Cache-Control") == "private, max-age=300"

        and: "its other files with their type, a folder by its index.html, a name with a space and an umlaut"
        read(address + "style.css").contentType.get().toString().startsWith("text/css")
        read(address + "anleitung/").body() == "<p>Schritt 1</p>"
        read(address + "anleitung").body() == "<p>Schritt 1</p>"
        read(address + "%C3%9Cber%20uns.html").body() == "<p>Musterfirma</p>"

        and: "a folder whose name has a space, linked without its slash, by its index.html"
        read(address + "Unser%20Team").body() == "<p>Team</p>"

        and: "nothing else"
        status(address + "setup.exe") == HttpStatus.NOT_FOUND
        status(address + "fehlt.html") == HttpStatus.NOT_FOUND
    }

    /**
     * A sandboxed page has the origin "null": Firefox sends it with every request of the page, every browser with a
     * font's - refused by the CORS filter (on localhost) or without Access-Control-Allow-Origin (a font is dropped).
     */
    def "the website's own requests - origin null - get its files, fonts included"() {
        given:
        def admin = api.tenantAdminToken(newTenant().id)
        def site = upload(admin, ["index.html": INDEX, "style.css": "h1 {}".bytes, "schrift.woff2": [1, 2] as byte[]]).site
        def address = addressFor(admin, "/tenant/admin/doc-sites/${site.id}/address")

        when:
        def css = client.toBlocking().exchange(HttpRequest.GET(address + "style.css").header("Origin", "null"), String)
        def font = client.toBlocking().exchange(HttpRequest.GET(address + "schrift.woff2").header("Origin", "null"), byte[])

        then:
        css.status == HttpStatus.OK
        css.contentType.get().toString().startsWith("text/css")
        font.status == HttpStatus.OK
        font.header("Access-Control-Allow-Origin") == "null"
    }

    def "a user reads the tenant's website and those of its areas; a tenant admin every one"() {
        given:
        def tenant = newTenant()
        def admin = api.tenantAdminToken(tenant.id)
        def sales = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def workshop = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Werkstatt"]) as Map
        def seller = api.user(tenant.id)
        api.group(admin, [seller.id], [sales.id])
        def everyone = upload(admin, ["index.html": INDEX]).site
        def salesSite = upload(admin, ["index.html": INDEX], sales.id).site
        def workshopSite = upload(admin, ["index.html": INDEX], workshop.id).site

        expect:
        (api.call(seller.token, "GET", "/tenant/doc-sites") as List).collect { [it.id, it.areaName] } ==
                [[everyone.id, null], [salesSite.id, "Vertrieb"]]
        (api.call(admin, "GET", "/tenant/doc-sites") as List)*.id as Set == [everyone.id, salesSite.id, workshopSite.id] as Set

        and: "another area's website answers like an unknown one"
        api.status(seller.token, "POST", "/tenant/doc-sites/${workshopSite.id}/address") == HttpStatus.NOT_FOUND
        api.status(seller.token, "POST", "/tenant/doc-sites/${salesSite.id}/address") == HttpStatus.OK
        api.status(admin, "POST", "/tenant/doc-sites/${workshopSite.id}/address") == HttpStatus.OK
    }

    def "a new ZIP replaces the scope's website and its files; the other scopes stay"() {
        given:
        def tenant = newTenant()
        def admin = api.tenantAdminToken(tenant.id)
        def area = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def first = upload(admin, ["index.html": INDEX, "alt.html": "<p>alt</p>".bytes]).site
        def other = upload(admin, ["index.html": "<p>Vertrieb</p>".bytes], area.id).site
        def oldPrefix = service.findAll(tenant.id as Long).find { it.id() == first.id }.storagePrefix()

        when:
        def second = upload(admin, ["index.html": "<p>neu</p>".bytes]).site
        def address = addressFor(admin, "/tenant/admin/doc-sites/${second.id}/address")

        then: "the same website, now with the new files only"
        second.id == first.id
        second.fileCount == 1
        read(address).body() == "<p>neu</p>"
        status(address + "alt.html") == HttpStatus.NOT_FOUND
        storage.open(oldPrefix + "index.html").isEmpty()

        and:
        read(addressFor(admin, "/tenant/admin/doc-sites/${other.id}/address")).body() == "<p>Vertrieb</p>"
    }

    def "a zipped folder is served from that folder"() {
        given:
        def admin = api.tenantAdminToken(newTenant().id)

        when:
        def site = upload(admin, ["handbuch/index.html": INDEX, "handbuch/style.css": "p {}".bytes]).site
        def address = addressFor(admin, "/tenant/admin/doc-sites/${site.id}/address")

        then:
        read(address).body() == new String(INDEX)
        read(address + "style.css").status == HttpStatus.OK
    }

    def "a ZIP is refused: #reason"() {
        given:
        def tenant = newTenant()
        def admin = api.tenantAdminToken(tenant.id)

        when:
        def response = uploadRefused(admin, archive, areaId)

        then:
        response.status == HttpStatus.BAD_REQUEST
        response.code == code
        (api.call(admin, "GET", "/tenant/admin/doc-sites") as Map).sites.isEmpty()

        where:
        reason                          | archive                                                           | areaId | code
        "no index.html at its top"      | AiApi.zip(["seite.html": INDEX])                                  | null   | "docSite.noIndex"
        "index.html in two folders"     | AiApi.zip(["a/index.html": INDEX, "b/index.html": INDEX])         | null   | "docSite.noIndex"
        "only files of other types"     | AiApi.zip(["index.php": INDEX])                                   | null   | "docSite.noIndex"
        "no ZIP at all"                 | INDEX                                                             | null   | "archive.unreadable"
        "an unknown area"               | AiApi.zip(["index.html": INDEX])                                  | 999999 | "knowledgeArea.unknown"
        "more than 2000 files"          | AiApi.zip((0..2000).collectEntries { [("seite-${it}.html".toString()): INDEX] }) | null | "archive.tooManyEntries"
    }

    def "a ZIP larger than app.doc-site.max-zip-size (100 MB) is refused before it is read, and a refused one leaves no files"() {
        given:
        def tenant = newTenant()
        def zip = Files.createTempFile("site-", ".zip")
        Files.write(zip, AiApi.zip(["index.html": INDEX]))

        when:
        service.replace(tenant.id as Long, null, service.maxZipBytes() + 1, zip)

        then:
        def e = thrown(ServiceException)
        e.code == "docSite.tooLarge"
        e.params == [maxMb: "100"]

        when:
        Files.write(zip, AiApi.zip(["seite.html": INDEX]))
        service.replace(tenant.id as Long, null, 100, zip)

        then:
        thrown(ServiceException)
        !Files.exists(java.nio.file.Path.of("build/test-storage/tenants/${tenant.id}/doc-sites")) ||
                Files.list(java.nio.file.Path.of("build/test-storage/tenants/${tenant.id}/doc-sites")).count() == 0

        cleanup:
        Files.deleteIfExists(zip)
    }

    def "an address works for its website only, for an hour, and never outside the website"() {
        given:
        def tenant = newTenant()
        def admin = api.tenantAdminToken(tenant.id)
        def site = upload(admin, ["index.html": INDEX]).site
        def address = addressFor(admin, "/tenant/admin/doc-sites/${site.id}/address")
        def token = address.split("/")[2]

        expect: "a forged, expired or broken token opens nothing"
        status("/doc-sites/${token.substring(0, token.length() - 2)}xx/") == HttpStatus.NOT_FOUND
        status("/doc-sites/${tenant.id}.${site.id}.${Instant.now().plusSeconds(3600).epochSecond}.abc/") == HttpStatus.NOT_FOUND
        status("/doc-sites/${tokens.create(tenant.id as Long, site.id as Long, Instant.now().minusSeconds(1))}/") == HttpStatus.NOT_FOUND
        status("/doc-sites/kein-token/") == HttpStatus.NOT_FOUND

        and: "the start without its slash is redirected below it - for a signed token only, never to another site"
        unfollowed("/doc-sites/${token}") == [301, "${token}/"]
        // reaches no route: since Micronaut 5.2.6 an anonymous request there is refused with 401 - no redirect either way
        unfollowed("/doc-sites/%2F%2Fevil.example") == [401, null]
        unfollowed("/doc-sites/http:evil.example") == [404, null]
        unfollowed("/doc-sites/${token.substring(0, token.length() - 2)}xx") == [404, null]

        and: "a path leaving the website or a hidden file is not served"
        service.open(token, "../../chat/x").isEmpty()
        service.open(token, "a/../index.html").isEmpty()
        service.open(token, ".hidden/index.html").isEmpty()
        service.open(token, "/index.html").isEmpty()
        service.open(token, "a\\index.html").isEmpty()
        status(address + "%2E%2E/%2E%2E/index.html") == HttpStatus.NOT_FOUND

        and: "a deactivated tenant's addresses end at once"
        api.admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, active: false])
        status(address) == HttpStatus.NOT_FOUND
    }

    def "a folder named without its slash is redirected to it, encoded: #name"() {
        expect:
        DocSiteService.folderLocation(name) == location

        where:
        name         | location
        "anleitung"  | "./anleitung/"
        "Unser Team" | "./Unser%20Team/"
        "Über uns"   | "./%C3%9Cber%20uns/"
        "teil:2"     | "./teil:2/"
    }

    def "another tenant's website is unknown"() {
        given:
        def owner = newTenant()
        def site = upload(api.tenantAdminToken(owner.id), ["index.html": INDEX]).site
        def stranger = api.tenantAdminToken(newTenant().id)

        expect:
        api.status(stranger, "DELETE", "/tenant/admin/doc-sites/${site.id}") == HttpStatus.NOT_FOUND
        api.status(stranger, "POST", "/tenant/admin/doc-sites/${site.id}/address") == HttpStatus.NOT_FOUND
        api.status(stranger, "POST", "/tenant/doc-sites/${site.id}/address") == HttpStatus.NOT_FOUND
        (api.call(stranger, "GET", "/tenant/doc-sites") as List).isEmpty()
    }

    def "an area with a website cannot be deleted until the website is removed - its files go with it"() {
        given:
        def tenant = newTenant()
        def admin = api.tenantAdminToken(tenant.id)
        def area = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def site = upload(admin, ["index.html": INDEX], area.id).site
        def prefix = service.findAll(tenant.id as Long).first().storagePrefix()

        expect:
        api.status(admin, "DELETE", "/tenant/admin/knowledge-areas/${area.id}") == HttpStatus.CONFLICT

        when:
        def deleted = api.status(admin, "DELETE", "/tenant/admin/doc-sites/${site.id}")

        then:
        deleted == HttpStatus.NO_CONTENT
        storage.open(prefix + "index.html").isEmpty()
        api.status(admin, "DELETE", "/tenant/admin/doc-sites/${site.id}") == HttpStatus.NOT_FOUND
        api.status(admin, "DELETE", "/tenant/admin/knowledge-areas/${area.id}") == HttpStatus.NO_CONTENT
    }

    def "the admin keeps a tenant's websites like its tenant admins - never through another tenant's path"() {
        given:
        def tenant = newTenant()
        def other = newTenant()
        def path = "/admin/tenants/${tenant.id}/doc-sites"

        when:
        def site = upload(api.adminToken, ["index.html": INDEX], null, path).site

        then:
        (api.admin("GET", path) as Map).sites*.id == [site.id]
        (api.admin("GET", path) as Map).maxZipBytes == 100L * 1024 * 1024
        read(addressFor(api.adminToken, "${path}/${site.id}/address")).body() == new String(INDEX)
        (api.call(api.userToken(tenant.id), "GET", "/tenant/doc-sites") as List)*.id == [site.id]
        api.status(api.adminToken, "DELETE", "/admin/tenants/${other.id}/doc-sites/${site.id}") == HttpStatus.NOT_FOUND
        api.status(api.adminToken, "DELETE", "${path}/${site.id}") == HttpStatus.NO_CONTENT
    }

    def "deleting the tenant deletes its websites' files"() {
        given:
        def tenant = api.tenant()
        upload(api.tenantAdminToken(tenant.id), ["index.html": INDEX])
        def prefix = service.findAll(tenant.id as Long).first().storagePrefix()

        when:
        def deleted = api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}")

        then:
        deleted == HttpStatus.NO_CONTENT
        storage.open(prefix + "index.html").isEmpty()
    }

    private Map newTenant() {
        Map tenant = api.tenant()
        tenants << tenant.id
        tenant
    }

    private Map upload(String token, Map<String, byte[]> entries, Object areaId = null, String path = "/tenant/admin/doc-sites") {
        client.toBlocking().retrieve(uploadRequest(token, AiApi.zip(entries), areaId, path), Map)
    }

    private Map uploadRefused(String token, byte[] archive, Object areaId) {
        try {
            client.toBlocking().retrieve(uploadRequest(token, archive, areaId, "/tenant/admin/doc-sites"), Map)
            throw new AssertionError("The upload was not refused")
        } catch (HttpClientResponseException e) {
            [status: e.status, code: e.response.getBody(Map).map { it.code }.orElse(null)]
        }
    }

    private static HttpRequest<?> uploadRequest(String token, byte[] archive, Object areaId, String path) {
        def body = MultipartBody.builder().addPart("file", "website.zip", MediaType.APPLICATION_OCTET_STREAM_TYPE, archive)
        if (areaId != null) {
            body.addPart("areaId", areaId.toString())
        }
        HttpRequest.POST(path, body.build()).contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token)
    }

    /** The website's address, absolute: "/doc-sites/<token>/". */
    private String addressFor(String token, String path) {
        "/" + (api.call(token, "POST", path) as Map).path
    }

    private HttpResponse<String> read(String path) {
        client.toBlocking().exchange(HttpRequest.GET(path), String)
    }

    /** Status and Location of an answer, its redirect not followed - the spec's client would follow it. */
    private List<Object> unfollowed(String path) {
        def response = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
                .send(java.net.http.HttpRequest.newBuilder(server.URI.resolve(path)).build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding())
        [response.statusCode(), response.headers().firstValue("Location").orElse(null)]
    }

    private HttpStatus status(String path) {
        try {
            read(path).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }
}
