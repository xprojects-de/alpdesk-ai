package de.alpdesk.controller.api.v1

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.core.type.Argument
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

/** The client software uploads, lists and deletes the tenant's documents with its API key - the same ones the users see. */
@MicronautTest(transactional = false)
class DocumentApiSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "an uploaded document is embedded and the tenant's users see it"() {
        given:
        def tenant = api.tenant()
        def userToken = userWithModel(tenant)
        def key = apiKey(tenant)

        when:
        def uploaded = upload(key, "preise.csv", "Leistung;Preis\nWartung;10 Euro")

        then:
        uploaded.contentType == "text/csv"
        api.awaitDocument(userToken, uploaded.id).status == "READY"
        list(key).collect { it.id } == [uploaded.id]

        when:
        def status = api.status(key, "DELETE", "/api/v1/documents/${uploaded.id}")

        then:
        status == HttpStatus.NO_CONTENT
        list(key) == []
        api.call(userToken, "GET", "/tenant/admin/documents") == []
    }

    def "a file type that is not allowed is refused with 415"() {
        given:
        def tenant = api.tenant()
        userWithModel(tenant)
        // A ZIP archive - whatever its name says: the type comes from the content.
        def zip = AiApi.zip(["a.txt": "Text".bytes])

        when:
        client.toBlocking().exchange(request(apiKey(tenant), "scan.txt", zip))

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNSUPPORTED_MEDIA_TYPE
    }

    def "documents belong to their tenant"() {
        given:
        def owner = api.tenant()
        userWithModel(owner)
        def document = upload(apiKey(owner), "a.txt", "Die Werkstatt ist offen")
        // Embedded before the next feature resets the fake - its embedding would count there.
        awaitDocument(owner, document.id)
        def other = api.tenant()
        def otherKey = apiKey(other)

        expect:
        list(otherKey) == []
        api.status(otherKey, "DELETE", "/api/v1/documents/${document.id}") == HttpStatus.NOT_FOUND
    }

    def "a push with a known externalId replaces that document in place - an unchanged one is left as it is"() {
        given:
        def tenant = api.tenant()
        userWithModel(tenant)
        def key = apiKey(tenant)

        when:
        def created = push(key, "handbuch.txt", "Die Maschine wird täglich gewartet.", "manual-4711")
        def first = awaitDocument(tenant, created.body.id)

        then:
        created.status == HttpStatus.CREATED
        created.body.origin == "API"
        created.body.externalId == "manual-4711"
        first.status == "READY"
        fake.embeddings.size() == 1

        when: "a new version under the same key"
        def replaced = push(key, "handbuch-v2.txt", "Die Maschine wird wöchentlich gewartet.", " manual-4711 ")

        then: "the same document, embedded again"
        replaced.status == HttpStatus.OK
        replaced.body.id == created.body.id
        replaced.body.fileName == "handbuch-v2.txt"
        awaitDocument(tenant, created.body.id).status == "READY"
        list(key).collect { it.id } == [created.body.id]
        fake.embeddings.size() == 2

        when: "the same version again"
        def unchanged = push(key, "handbuch-v2.txt", "Die Maschine wird wöchentlich gewartet.", "manual-4711")

        then: "nothing is embedded - no cost"
        unchanged.status == HttpStatus.OK
        unchanged.body.id == created.body.id
        unchanged.body.status == "READY"
        fake.embeddings.size() == 2
    }

    def "an unchanged push embeds a document again whose embedding failed"() {
        given:
        def tenant = api.tenant()
        userWithModel(tenant)
        def key = apiKey(tenant)
        fake.failures = 1
        def created = push(key, "a.txt", "Text", "doc-1")

        expect:
        awaitDocument(tenant, created.body.id).status == "FAILED"

        when:
        def again = push(key, "a.txt", "Text", "doc-1")

        then:
        again.status == HttpStatus.OK
        awaitDocument(tenant, created.body.id).status == "READY"
    }

    def "without an externalId every push is a new document"() {
        given:
        def tenant = api.tenant()
        userWithModel(tenant)
        def key = apiKey(tenant)

        when:
        upload(key, "a.txt", "Text")
        upload(key, "a.txt", "Text")

        then:
        list(key).size() == 2
        list(key).every { it.externalId == null && it.origin == "API" }
    }

    def "a document is deleted by its externalId"() {
        given:
        def tenant = api.tenant()
        userWithModel(tenant)
        def key = apiKey(tenant)
        def created = push(key, "a.txt", "Text", "ordner/datei 1.txt")

        expect:
        api.status(key, "DELETE", "/api/v1/documents?externalId=${URLEncoder.encode("ordner/datei 1.txt", "UTF-8")}") == HttpStatus.NO_CONTENT
        list(key) == []
        api.status(key, "DELETE", "/api/v1/documents?externalId=ordner%2Fdatei%201.txt") == HttpStatus.NOT_FOUND
        api.status(key, "DELETE", "/api/v1/documents") == HttpStatus.BAD_REQUEST
        created.status == HttpStatus.CREATED
    }

    def "an invalid externalId is refused: #reason"() {
        given:
        def tenant = api.tenant()
        userWithModel(tenant)

        when:
        push(apiKey(tenant), "a.txt", "Text", externalId)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        reason     | externalId
        "blank"    | "   "
        "too long" | "x" * 201
    }

    def "an externalId is the tenant's own - another tenant's same key is another document"() {
        given:
        def first = api.tenant()
        def second = api.tenant()
        userWithModel(first)
        userWithModel(second)
        def firstKey = apiKey(first)
        def secondKey = apiKey(second)

        when:
        def a = push(firstKey, "a.txt", "Erster Mandant", "same-key")
        def b = push(secondKey, "b.txt", "Zweiter Mandant", "same-key")

        then:
        a.status == HttpStatus.CREATED
        b.status == HttpStatus.CREATED
        api.status(secondKey, "DELETE", "/api/v1/documents?externalId=same-key") == HttpStatus.NO_CONTENT
        list(firstKey).collect { it.externalId } == ["same-key"]
    }

    def "a document uploaded in the frontend has origin UPLOAD and no externalId"() {
        given:
        def tenant = api.tenant()
        def token = userWithModel(tenant)

        when:
        def uploaded = api.upload(token, "a.txt", "Text")

        then:
        uploaded.origin == "UPLOAD"
        uploaded.externalId == null
    }

    private String userWithModel(Map tenant) {
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        token
    }

    private String apiKey(Map tenant) {
        api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Fachsoftware"]).plainKey
    }

    private Map upload(String key, String fileName, String text) {
        client.toBlocking().retrieve(request(key, fileName, text.getBytes("UTF-8")), Map)
    }

    private HttpRequest<?> request(String key, String fileName, byte[] content, String externalId = null) {
        def body = MultipartBody.builder().addPart("file", fileName, MediaType.APPLICATION_OCTET_STREAM_TYPE, content)
        if (externalId != null) {
            body.addPart("externalId", externalId)
        }
        HttpRequest.POST("/api/v1/documents", body.build()).contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(key)
    }

    /** Pushes a document with an externalId: [status: ..., body: the document]. */
    private Map push(String key, String fileName, String text, String externalId) {
        def response = client.toBlocking().exchange(request(key, fileName, text.getBytes("UTF-8"), externalId), Map)
        [status: response.status, body: response.body()]
    }

    /** Waits until the background embedding of the document is done, as the admin sees it. */
    private Map awaitDocument(Map tenant, Object documentId) {
        api.awaitDocument(api.adminToken, documentId, "/admin/tenants/${tenant.id}/documents")
    }

    private List<Map> list(String key) {
        client.toBlocking().retrieve(HttpRequest.GET("/api/v1/documents").bearerAuth(key), Argument.listOf(Map))
    }
}
