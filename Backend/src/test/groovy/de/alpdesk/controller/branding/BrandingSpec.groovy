package de.alpdesk.controller.branding

import de.alpdesk.services.ai.AiApi
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

/**
 * The look of the frontend: the installation's default (app.branding.*, anonymous) and a tenant's own, set by the
 * admin only and seen by the tenant's users and on the tenant's own login page /login/<key> - each value falling back
 * to the default.
 */
@MicronautTest(transactional = false)
class BrandingSpec extends Specification {

    static final byte[] PNG = Base64.decoder.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
    static final Path STORAGE = Path.of("build/test-storage")

    @Inject
    @Client("/")
    HttpClient client

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
    }

    def "anyone gets the installation's look - the login page needs it before a login - with the logo in the jar"() {
        expect:
        api.call(null, "GET", "/branding") == [name: "Zirmo", primaryColor: "#49732a", hasLogo: true]
        def logo = api.download(null, "/branding/logo")
        logo.contentType.get().toString() == "image/png"
        logo.body() == BrandingSpec.getResourceAsStream("/branding/default-logo.png").bytes
    }

    def "anyone gets the web app manifest with the installation's look - addresses relative to it, no tenant's look"() {
        given: "a tenant with a look of its own - the manifest is the installation's, read before anyone signs in"
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}/branding", [name: "Musterbau GmbH", primaryColor: "#1A4F8B"])

        when:
        def response = client.toBlocking().exchange(HttpRequest.GET("/branding/manifest.webmanifest"), String)

        then:
        response.contentType.get().toString() == "application/manifest+json"
        api.call(null, "GET", "/branding/manifest.webmanifest") == [
                name            : "Zirmo",
                short_name      : "Zirmo",
                start_url       : "../",
                scope           : "../",
                display         : "standalone",
                theme_color     : "#49732a",
                background_color: "#111311",
                icons           : [[src: "logo", type: "image/png", sizes: "any"]]]
    }

    def "the admin gives a tenant its own look; its users see it, another tenant's the default"() {
        given:
        def tenant = api.tenant()
        def user = api.userToken(tenant.id)
        def path = "/admin/tenants/${tenant.id}/branding"

        when:
        def saved = api.admin("PUT", path, [name: "  Musterbau GmbH ", primaryColor: "#1A4F8B"])
        uploadLogo(tenant.id, PNG)

        then:
        saved == [name: "Musterbau GmbH", primaryColor: "#1a4f8b", hasLogo: false, defaultName: "Zirmo",
                  defaultPrimaryColor: "#49732a", loginKey: saved.loginKey]
        api.call(user, "GET", "/tenant/branding") == [name: "Musterbau GmbH", primaryColor: "#1a4f8b", hasLogo: true]
        def logo = api.download(user, "/tenant/branding/logo")
        logo.body() == PNG
        logo.contentType.get().toString() == "image/png"

        and: "another tenant has the installation's look, its logo included"
        api.call(api.userToken(api.tenant().id), "GET", "/tenant/branding") ==
                [name: "Zirmo", primaryColor: "#49732a", hasLogo: true]
    }

    def "a value left empty falls back to the default, value by value"() {
        given:
        def tenant = api.tenant()
        def user = api.userToken(tenant.id)
        def path = "/admin/tenants/${tenant.id}/branding"

        when:
        api.admin("PUT", path, [primaryColor: "#aa0000"])

        then:
        api.call(user, "GET", "/tenant/branding") == [name: "Zirmo", primaryColor: "#aa0000", hasLogo: true]

        when:
        api.admin("PUT", path, [name: "Firma X", primaryColor: " "])

        then:
        api.call(user, "GET", "/tenant/branding") == [name: "Firma X", primaryColor: "#49732a", hasLogo: true]
    }

    def "invalid values are refused: #reason"() {
        given:
        def tenant = api.tenant()

        expect:
        api.status(api.adminToken, "PUT", "/admin/tenants/${tenant.id}/branding", body) == HttpStatus.BAD_REQUEST

        where:
        reason               | body
        "a name too long"    | [name: "x" * 61]
        "a colour by name"   | [primaryColor: "red"]
        "a colour too short" | [primaryColor: "#12345"]
        "a colour not hex"   | [primaryColor: "#12345g"]
    }

    def "a logo must be a small PNG, JPEG or WebP: #reason"() {
        given:
        def tenant = api.tenant()

        expect:
        logoStatus(tenant.id, content) == status

        where:
        reason        | content                                                                   | status
        "an SVG"      | '<svg xmlns="http://www.w3.org/2000/svg"><script>x()</script></svg>'.bytes | HttpStatus.UNSUPPORTED_MEDIA_TYPE
        "a GIF"       | "GIF89a....".bytes                                                        | HttpStatus.UNSUPPORTED_MEDIA_TYPE
        "too large"   | Arrays.copyOf(PNG, 512 * 1024 + 1)                                        | HttpStatus.BAD_REQUEST
        "a JPEG"      | [0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10] as byte[]                               | HttpStatus.OK
        "a WebP"      | "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".bytes                              | HttpStatus.OK
    }

    def "removing the logo shows the default again; the tenant's deletion removes it"() {
        given:
        def tenant = api.tenant()
        def user = api.userToken(tenant.id)
        uploadLogo(tenant.id, PNG)
        def stored = STORAGE.resolve("tenants/${tenant.id}/branding/logo")

        expect:
        Files.exists(stored)

        when:
        def deleted = api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}/branding/logo")

        then:
        deleted == HttpStatus.NO_CONTENT
        !Files.exists(stored)
        api.download(user, "/tenant/branding/logo").body() == BrandingSpec.getResourceAsStream("/branding/default-logo.png").bytes
        api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}/branding/logo") == HttpStatus.NOT_FOUND

        when:
        uploadLogo(tenant.id, PNG)
        api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}")

        then:
        !Files.exists(stored)
    }

    def "an unknown tenant has no look to set"() {
        expect:
        api.status(api.adminToken, "GET", "/admin/tenants/999999999/branding") == HttpStatus.NOT_FOUND
        api.status(api.adminToken, "PUT", "/admin/tenants/999999999/branding", [name: "X"]) == HttpStatus.NOT_FOUND
        logoStatus(999999999, PNG) == HttpStatus.NOT_FOUND
    }

    def "only the admin sets a tenant's look - a tenant admin and a user do not"() {
        given:
        def tenant = api.tenant()

        expect:
        api.status(api.tenantAdminToken(tenant.id), "PUT", "/admin/tenants/${tenant.id}/branding", [name: "X"]) == HttpStatus.FORBIDDEN
        api.status(api.userToken(tenant.id), "GET", "/admin/tenants/${tenant.id}/branding") == HttpStatus.FORBIDDEN
        api.status(null, "GET", "/tenant/branding") == HttpStatus.UNAUTHORIZED
    }

    def "anyone sees a tenant's look on its own login page - with its logo, else the installation's"() {
        given:
        def tenant = api.tenant()
        api.admin("PUT", "/admin/tenants/${tenant.id}/branding", [name: "Musterbau GmbH", primaryColor: "#1a4f8b"])
        uploadLogo(tenant.id, PNG)
        def key = loginKey(tenant.id)
        def plain = api.tenant()

        expect:
        key ==~ /[0-9a-f]{32}/
        api.call(null, "GET", "/branding/login/${key}") == [name: "Musterbau GmbH", primaryColor: "#1a4f8b", hasLogo: true]
        api.download(null, "/branding/login/${key}/logo").body() == PNG

        and: "a tenant without a look of its own shows the installation's"
        api.call(null, "GET", "/branding/login/${loginKey(plain.id)}") == [name: "Zirmo", primaryColor: "#49732a", hasLogo: true]
        api.download(null, "/branding/login/${loginKey(plain.id)}/logo").body() ==
                BrandingSpec.getResourceAsStream("/branding/default-logo.png").bytes

        and: "every tenant has a key of its own"
        loginKey(plain.id) != key
    }

    def "an unknown key, a deactivated or a deleted tenant leave the login page neutral: #reason"() {
        given:
        def tenant = api.tenant()
        def key = loginKey(tenant.id)
        change(tenant)

        expect:
        api.status(null, "GET", "/branding/login/${lookedUp(key)}") == HttpStatus.NOT_FOUND
        api.status(null, "GET", "/branding/login/${lookedUp(key)}/logo") == HttpStatus.NOT_FOUND

        where:
        reason        | change                                                                                   | lookedUp
        "unknown key" | { t -> }                                                                                 | { k -> "0" * 32 }
        "deactivated" | { t -> api.admin("PUT", "/admin/tenants/${t.id}", [name: t.name, active: false]) }       | { k -> k }
        "deleted"     | { t -> api.status(api.adminToken, "DELETE", "/admin/tenants/${t.id}") }                  | { k -> k }
    }

    def "the admin renews the address: the old one is gone, the new one shows the look"() {
        given:
        def tenant = api.tenant()
        def old = loginKey(tenant.id)

        when:
        def renewed = api.admin("POST", "/admin/tenants/${tenant.id}/branding/login-key").loginKey

        then:
        renewed != old
        loginKey(tenant.id) == renewed
        api.status(null, "GET", "/branding/login/${old}") == HttpStatus.NOT_FOUND
        api.status(null, "GET", "/branding/login/${renewed}") == HttpStatus.OK
        api.status(api.adminToken, "POST", "/admin/tenants/999999999/branding/login-key") == HttpStatus.NOT_FOUND
    }

    def "a tenant admin reads the address of its own tenant's login page and renews none"() {
        given:
        def tenant = api.tenant()
        def other = api.tenant()
        def tenantAdmin = api.tenantAdminToken(tenant.id)

        expect:
        api.call(tenantAdmin, "GET", "/tenant/admin/branding/login-key") == [loginKey: loginKey(tenant.id)]
        api.call(api.tenantAdminToken(other.id), "GET", "/tenant/admin/branding/login-key") == [loginKey: loginKey(other.id)]
        api.status(tenantAdmin, "POST", "/admin/tenants/${tenant.id}/branding/login-key") == HttpStatus.FORBIDDEN
        api.status(api.userToken(tenant.id), "GET", "/tenant/admin/branding/login-key") == HttpStatus.FORBIDDEN
    }

    def "the sign-in through the WAM proxy keeps its address next to the tenants' login pages"() {
        expect:
        (api.call(null, "GET", "/login/wam") as Map).containsKey("enabled")
    }

    private String loginKey(Object tenantId) {
        api.admin("GET", "/admin/tenants/${tenantId}/branding").loginKey
    }

    private void uploadLogo(Object tenantId, byte[] content) {
        client.toBlocking().exchange(logoRequest(tenantId, content))
    }

    private HttpStatus logoStatus(Object tenantId, byte[] content) {
        try {
            client.toBlocking().exchange(logoRequest(tenantId, content)).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    private HttpRequest<?> logoRequest(Object tenantId, byte[] content) {
        def body = MultipartBody.builder().addPart("file", "logo", MediaType.APPLICATION_OCTET_STREAM_TYPE, content).build()
        HttpRequest.PUT("/admin/tenants/${tenantId}/branding/logo", body)
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(api.adminToken)
    }
}
