package de.alpdesk.services.document

import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/** app.search.mode switches how the chat searches without a new build - here to the vector search alone. */
@MicronautTest(transactional = false)
@Property(name = "app.search.mode", value = "VECTOR")
class KnowledgeSearchModeSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    KnowledgeSearch knowledge

    @Inject
    TenantRepository tenants

    def "with app.search.mode VECTOR the chat's search does not use the full text"() {
        given:
        def tenant = KnowledgeSearchSpec.tenantWithDocuments(new AiApi(client), tenants, ["wartung.txt": "Die Wartung erfolgt jährlich."])

        expect: "the stem is the full text's - the vectors (word hashes in the specs) do not know it"
        knowledge.search(tenant, null, KnowledgeScope.everything(), "Wartungen", 5) == []
    }
}
