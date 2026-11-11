package de.alpdesk.controller.document

import de.alpdesk.entities.tenant.Tenant
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.document.KnowledgeSearchSpec
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * "Alle Dokumente neu einbetten": every document of a tenant embedded again with its model - so documents embedded
 * before a change of the chunks (their context header) get it without being uploaded anew.
 */
@MicronautTest(transactional = false)
class EmbedAgainSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "a tenant admin embeds every document again: the chunks get today's header, only its own tenant's"() {
        given: "two tenants whose chunks carry the header of before - front matter's '---' as the title"
        def tenant = tenantWithOldHeaders()
        def other = tenantWithOldHeaders()
        def token = api.tenantAdminToken(tenant.id())
        int embeddedBefore = fake.embeddings.size()

        when:
        def response = api.call(token, "POST", "/tenant/admin/documents/embed-again")

        then:
        response == [documents: 2]
        (api.call(token, "GET", "/tenant/admin/documents") as List<Map>).collect { api.awaitDocument(token, it.id).status } ==
                ["READY", "READY"]
        headers(tenant) == ["a.md – title: \"Serie NK24xx\"", "b.md – title: \"Serie BX12\""]
        fake.embeddings.size() > embeddedBefore

        and: "the other tenant's chunks are as they were"
        headers(other) == ["a.md – ---", "b.md – ---"]
    }

    def "the admin embeds a tenant's documents again like a tenant admin"() {
        given:
        def tenant = tenantWithOldHeaders()
        def token = api.tenantAdminToken(tenant.id())

        when:
        def response = api.admin("POST", "/admin/tenants/${tenant.id()}/documents/embed-again")
        (api.call(token, "GET", "/tenant/admin/documents") as List<Map>).each { api.awaitDocument(token, it.id) }

        then:
        response == [documents: 2]
        headers(tenant) == ["a.md – title: \"Serie NK24xx\"", "b.md – title: \"Serie BX12\""]
    }

    def "a tenant without documents has nothing to embed again"() {
        given:
        def tenant = KnowledgeSearchSpec.tenantWithDocuments(api, tenants, [:])

        expect:
        api.call(api.tenantAdminToken(tenant.id()), "POST", "/tenant/admin/documents/embed-again") == [documents: 0]
    }

    def "refused without an embedding model or without a key for it - every document would end failed"() {
        given: "a tenant without a model, and one whose key was removed"
        def withoutModel = api.tenant()
        def withoutKey = tenantWithOldHeaders()
        def token = api.tenantAdminToken(withoutKey.id())
        assert api.status(token, "DELETE", "/tenant/admin/provider-keys/OPENAI") == HttpStatus.NO_CONTENT

        expect:
        api.status(api.tenantAdminToken(withoutModel.id), "POST", "/tenant/admin/documents/embed-again") == HttpStatus.CONFLICT
        api.status(token, "POST", "/tenant/admin/documents/embed-again") == HttpStatus.CONFLICT

        and: "nothing was touched"
        (api.call(token, "GET", "/tenant/admin/documents") as List<Map>)*.status == ["READY", "READY"]
    }

    def "an unknown tenant answers 404"() {
        expect:
        api.status(api.adminToken, "POST", "/admin/tenants/999999999/documents/embed-again") == HttpStatus.NOT_FOUND
    }

    /** A tenant with two Markdown documents starting with front matter, their chunks' headers as before October 2026. */
    private Tenant tenantWithOldHeaders() {
        def tenant = KnowledgeSearchSpec.tenantWithDocuments(api, tenants, [
                "a.md": "---\ntitle: \"Serie NK24xx\"\n---\nDie Klemmen messen mit 24 Bit.",
                "b.md": "---\ntitle: \"Serie BX12\"\n---\nDie Klemmen schalten 24 V."])
        execute(tenant, "UPDATE embedding SET text = regexp_replace(text, '^([^ ]+) – [^\\n]*', '\\1 – ---')")
        assert headers(tenant) == ["a.md – ---", "b.md – ---"]
        tenant
    }

    /** The first line of every chunk - its context header -, by file. */
    private List<String> headers(Tenant tenant) {
        databases.openConnection(tenant.databaseName()).withCloseable { connection ->
            def result = connection.createStatement().executeQuery(
                    "SELECT split_part(text, E'\\n', 1) FROM embedding ORDER BY metadata->>'file_name'")
            def lines = []
            while (result.next()) {
                lines << result.getString(1)
            }
            lines
        }
    }

    private void execute(Tenant tenant, String sql) {
        databases.openConnection(tenant.databaseName()).withCloseable { connection ->
            connection.createStatement().execute(sql)
        }
    }
}
