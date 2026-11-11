package de.alpdesk.controller.document

import de.alpdesk.entities.tenant.SearchLanguage
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.document.KnowledgeScope
import de.alpdesk.services.document.KnowledgeSearch
import de.alpdesk.services.document.KnowledgeSearchSpec
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import static de.alpdesk.services.document.KnowledgeSearch.Mode.FULL_TEXT

/**
 * The language the full-text search reads a tenant's documents in: chosen by the admin when the tenant is created,
 * changed by the admin or a tenant admin - which builds the search's index again and embeds nothing.
 */
@MicronautTest(transactional = false)
class SearchLanguageSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Inject
    KnowledgeSearch knowledge

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "every search language is a text search configuration of PostgreSQL - the enum names what the server has"() {
        given:
        def tenant = api.tenant()

        expect: "a name missing here would fail every index build of a tenant choosing it"
        configurations(tenant.databaseName as String) == SearchLanguage.values().collect { it.config() }.toSet()
    }

    def "a new tenant is searched in no language unless the admin chooses one; an update leaves it"() {
        when:
        def plain = api.tenant()
        def english = api.tenant(false, "ENGLISH")
        def updated = api.admin("PUT", "/admin/tenants/${english.id}", [name: english.name, searchLanguage: "RUSSIAN"])

        then:
        plain.searchLanguage == "SIMPLE"
        english.searchLanguage == "ENGLISH"
        updated.searchLanguage == "ENGLISH"
        api.call(api.userToken(english.id), "GET", "/tenant/ai/settings").searchLanguage == "ENGLISH"
    }

    def "a tenant admin changes the language: the index is built for it, nothing is embedded again"() {
        given: "a tenant searched in German with an English document"
        def tenant = KnowledgeSearchSpec.tenantWithDocuments(api, tenants, ["a.txt": "The pumps are running."])
        def token = api.tenantAdminToken(tenant.id())
        int embeddedBefore = fake.embeddings.size()

        expect: "German stems do not find the English word form"
        found(tenant.id(), "run") == []

        when:
        def settings = api.call(token, "PUT", "/tenant/admin/search-language", [language: "ENGLISH"])

        then:
        settings.searchLanguage == "ENGLISH"
        awaitTextIndexes(tenant.databaseName(), ["embedding_text_english_v2_idx"])
        fake.embeddings.size() == embeddedBefore
        (api.call(token, "GET", "/tenant/admin/documents") as List)*.status == ["READY"]

        and:
        found(tenant.id(), "run") == ["a.txt"]
    }

    def "the admin changes a tenant's language like a tenant admin"() {
        given:
        def tenant = KnowledgeSearchSpec.tenantWithDocuments(api, tenants, ["a.txt": "Котлы обслуживаются ежегодно."])

        when:
        def response = api.admin("PUT", "/admin/tenants/${tenant.id()}/search-language", [language: "RUSSIAN"])

        then:
        response.searchLanguage == "RUSSIAN"
        awaitTextIndexes(tenant.databaseName(), ["embedding_text_russian_v2_idx"])
        found(tenant.id(), "котлов") == ["a.txt"]
    }

    def "a tenant without documents gets the language and no index - the first document builds it"() {
        given:
        def tenant = api.tenant()

        when:
        def response = api.admin("PUT", "/admin/tenants/${tenant.id}/search-language", [language: "FRENCH"])

        then:
        response.searchLanguage == "FRENCH"
        tenants.findById(tenant.id as Long).get().searchLanguage().name() == "FRENCH"
    }

    def "the language is refused when missing or unknown: #body"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)

        expect:
        api.status(token, "PUT", "/tenant/admin/search-language", body) == HttpStatus.BAD_REQUEST
        api.status(api.adminToken, "PUT", "/admin/tenants/${tenant.id}/search-language", body) == HttpStatus.BAD_REQUEST

        where:
        body << [[:], [language: null], [language: "KLINGON"], [language: "german"]]
    }

    def "a new tenant's language is refused when unknown: #language"() {
        expect:
        api.status(api.adminToken, "POST", "/admin/tenants", [name: "Firma " + AiApi.unique(), searchLanguage: language]) ==
                HttpStatus.BAD_REQUEST

        where:
        language << ["KLINGON", "german"]
    }

    def "a missing language names its rule"() {
        expect:
        api.error(api.tenantAdminToken(api.tenant().id), "PUT", "/tenant/admin/search-language", [:]) ==
                "The search language is missing"
    }

    def "an unknown tenant answers 404; a tenant admin changes only its own tenant"() {
        given:
        def own = api.tenant()
        def other = api.tenant()

        when:
        api.call(api.tenantAdminToken(own.id), "PUT", "/tenant/admin/search-language", [language: "ENGLISH"])

        then:
        api.status(api.adminToken, "PUT", "/admin/tenants/999999999/search-language", [language: "ENGLISH"]) ==
                HttpStatus.NOT_FOUND
        tenants.findById(own.id as Long).get().searchLanguage().name() == "ENGLISH"
        tenants.findById(other.id as Long).get().searchLanguage().name() == "SIMPLE"
    }

    private Set<String> configurations(String databaseName) {
        databases.openConnection(databaseName).withCloseable { connection ->
            def result = connection.createStatement().executeQuery("SELECT cfgname FROM pg_ts_config")
            def names = [] as Set
            while (result.next()) {
                names << result.getString(1)
            }
            names
        }
    }

    private List<String> found(Long tenantId, String query) {
        knowledge.search(tenants.findById(tenantId).get(), null, KnowledgeScope.everything(), query, FULL_TEXT)*.fileName()
    }

    /** Waits for the index built in the background: true once the table's full-text indexes are exactly these. */
    private boolean awaitTextIndexes(String databaseName, List<String> expected) {
        long deadline = System.currentTimeMillis() + 10_000
        while (textIndexes(databaseName) != expected) {
            assert System.currentTimeMillis() < deadline, "full-text indexes ${textIndexes(databaseName)}, expected ${expected}"
            Thread.sleep(100)
        }
        true
    }

    private List<String> textIndexes(String databaseName) {
        databases.dataSource(databaseName).connection.withCloseable { connection ->
            def result = connection.createStatement().executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'embedding' "
                    + "AND indexname LIKE 'embedding_text%' AND indexname IN (SELECT relname FROM pg_class c "
                    + "JOIN pg_index x ON x.indexrelid = c.oid WHERE x.indisvalid) ORDER BY 1")
            def names = []
            while (result.next()) {
                names << result.getString(1)
            }
            names
        }
    }
}
