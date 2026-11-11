package de.alpdesk.controller.docsite

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.docsite.DocSiteService
import io.micronaut.context.annotation.Property
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

/** The handbook's ZIP limit is a setting of the installation, told to the upload dialog by the list of websites. */
@MicronautTest(transactional = false)
@Property(name = "app.doc-site.max-zip-size", value = "2KB")
class DocSiteLimitSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Shared
    AiApi api

    List<Object> tenants = []

    def setup() {
        api = api ?: new AiApi(client)
    }

    def cleanup() {
        tenants.each { api.status(api.adminToken, "DELETE", "/admin/tenants/${it}") }
    }

    def "the admins learn the limit with the websites, and a ZIP above it is refused"() {
        given:
        def tenant = api.tenant()
        tenants << tenant.id
        def admin = api.tenantAdminToken(tenant.id)
        def small = AiApi.zip(["index.html": "<h1>Handbuch</h1>".bytes])
        def large = AiApi.zip(["index.html": new byte[4096].tap { new Random(7).nextBytes(it) }])

        expect:
        (api.call(admin, "GET", "/tenant/admin/doc-sites") as Map).maxZipBytes == 2048
        (api.admin("GET", "/admin/tenants/${tenant.id}/doc-sites") as Map).maxZipBytes == 2048

        when:
        def refused = upload(admin, large)

        then:
        refused.status == HttpStatus.BAD_REQUEST
        refused.code == "docSite.tooLarge"
        (api.call(admin, "GET", "/tenant/admin/doc-sites") as Map).sites.isEmpty()

        when:
        def stored = upload(admin, small)

        then:
        stored.status == HttpStatus.OK
        (api.call(admin, "GET", "/tenant/admin/doc-sites") as Map).sites.size() == 1
    }

    def "a limit above the server's request limit is capped at it"() {
        expect:
        DocSiteService.zipLimit(100, 512) == 100
        DocSiteService.zipLimit(1024, 512) == 512
    }

    private Map upload(String token, byte[] archive) {
        def body = MultipartBody.builder()
                .addPart("file", "website.zip", MediaType.APPLICATION_OCTET_STREAM_TYPE, archive).build()
        def request = HttpRequest.POST("/tenant/admin/doc-sites", body)
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token)
        try {
            client.toBlocking().exchange(request, Map)
            [status: HttpStatus.OK]
        } catch (HttpClientResponseException e) {
            [status: e.status, code: e.response.getBody(Map).map { it.code }.orElse(null)]
        }
    }
}
