package de.alpdesk.controller.document

import de.alpdesk.entities.chat.ConversationOwner
import de.alpdesk.entities.document.DocumentStatus
import de.alpdesk.repositories.chat.ConversationRepository
import de.alpdesk.repositories.document.DocumentRepository
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.tenant.TenantDatabases
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

/**
 * Knowledge areas (docs/architecture/ai-features.md "Knowledge areas"): a document without an area is the company
 * knowledge everyone finds; an area's documents are found only by the members of the groups and the keys assigned to it
 * and by the tenant admins - in the chat, through the client software's API and through the MCP server.
 */
@MicronautTest(transactional = false)
class KnowledgeAreaSpec extends Specification {

    static final String QUESTION = "Was steht in der Preisliste?"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    TenantRepository tenantEntities

    @Inject
    TenantDatabases databases

    @Inject
    DocumentRepository documents

    @Inject
    ConversationRepository conversations

    @Shared
    AiApi api

    /** The tenants of a feature - deleted afterwards, which closes their pools: the suite shares one PostgreSQL. */
    List<Object> tenants = []

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def cleanup() {
        tenants.each { api.status(api.adminToken, "DELETE", "/admin/tenants/${it}") }
    }

    private Map newTenant() {
        Map tenant = api.tenant()
        tenants << tenant.id
        tenant
    }

    def "a tenant admin creates, renames and deletes areas; the admin keeps the same list"() {
        given:
        def tenant = newTenant()
        def admin = api.tenantAdminToken(tenant.id)

        when:
        def sales = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "  Vertrieb "]) as Map
        def workshop = api.admin("POST", "/admin/tenants/${tenant.id}/knowledge-areas", [name: "Werkstatt"]) as Map
        def renamed = api.call(admin, "PUT", "/tenant/admin/knowledge-areas/${sales.id}", [name: "Verkauf"]) as Map

        then:
        sales.name == "Vertrieb"
        renamed.name == "Verkauf"
        (api.admin("GET", "/admin/tenants/${tenant.id}/knowledge-areas") as List).collect { it.name } == ["Verkauf", "Werkstatt"]

        when:
        def deleted = api.status(admin, "DELETE", "/tenant/admin/knowledge-areas/${workshop.id}")

        then:
        deleted == HttpStatus.NO_CONTENT
        (api.call(admin, "GET", "/tenant/admin/knowledge-areas") as List).collect { it.name } == ["Verkauf"]
    }

    def "an area's name is refused: #reason"() {
        given:
        def tenant = newTenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"])

        expect:
        api.status(admin, "POST", "/tenant/admin/knowledge-areas", [name: name]) == status

        where:
        reason               | name       | status
        "missing"            | null       | HttpStatus.BAD_REQUEST
        "blank"              | "   "      | HttpStatus.BAD_REQUEST
        "too long"           | "x" * 101  | HttpStatus.BAD_REQUEST
        "taken in any case"  | "VERTRIEB" | HttpStatus.CONFLICT
    }

    def "another tenant's area is not found, and assigning or uploading into it is refused"() {
        given:
        def tenant = newTenant()
        def other = newTenant()
        def otherArea = api.admin("POST", "/admin/tenants/${other.id}/knowledge-areas", [name: "Fremd"]) as Map
        def admin = userWithEmbedding(tenant)

        expect:
        api.status(admin, "PUT", "/tenant/admin/knowledge-areas/${otherArea.id}", [name: "X"]) == HttpStatus.NOT_FOUND
        api.status(admin, "DELETE", "/tenant/admin/knowledge-areas/${otherArea.id}") == HttpStatus.NOT_FOUND
        api.status(admin, "POST", "/tenant/admin/groups", [name: "G", areaIds: [otherArea.id]]) == HttpStatus.BAD_REQUEST
        api.status(admin, "POST", "/tenant/admin/api-keys", [name: "K", areaIds: [otherArea.id]]) == HttpStatus.BAD_REQUEST
        api.status(admin, "POST", "/tenant/admin/mcp-keys", [name: "K", areaIds: [otherArea.id]]) == HttpStatus.BAD_REQUEST
        uploadStatus(admin, "a.txt", "Text", otherArea.id) == HttpStatus.BAD_REQUEST
        api.status(admin, "DELETE", "/tenant/admin/documents/uploads?area=${otherArea.id}") == HttpStatus.BAD_REQUEST
    }

    def "a user finds the company knowledge and its groups' areas; a tenant admin finds every area"() {
        given:
        def setup = knowledge()

        expect:
        sources(setup, setup.plainUser) == ["allgemein.txt"]
        sources(setup, setup.salesUser) == ["allgemein.txt", "vertrieb.txt"]
        sources(setup, setup.admin) == ["allgemein.txt", "vertrieb.txt", "werkstatt.txt"]
    }

    def "a tenant admin whose rights were taken away finds only its groups' areas at once - its token still says admin"() {
        given:
        def setup = knowledge()
        def demoted = api.user(setup.tenant.id, true)

        expect:
        sources(setup, demoted.token as String) == ["allgemein.txt", "vertrieb.txt", "werkstatt.txt"]

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/users/${demoted.id}",
                [username: demoted.username, displayName: "Maria", admin: false, groupIds: [api.standardGroup(setup.tenant.id)]])

        then: "in Standard: the company knowledge only"
        sources(setup, demoted.token as String) == ["allgemein.txt"]
    }

    def "a user no longer finds an area's documents when its group loses the area"() {
        given:
        def setup = knowledge()

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/groups/${setup.salesGroup.id}", [name: setup.salesGroup.name, areaIds: []])

        then:
        sources(setup, setup.salesUser) == ["allgemein.txt"]
    }

    def "a user no longer finds an area's documents when it leaves the group - in Standard it still gets in"() {
        given:
        def setup = knowledge()
        def standard = api.standardGroup(setup.tenant.id)

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/users/${setup.salesUserId}",
                [username: setup.salesUsername, displayName: "Maria", groupIds: [standard]])

        then:
        sources(setup, setup.salesUser) == ["allgemein.txt"]
        (api.call(setup.admin, "GET", "/tenant/admin/users") as List<Map>).find { it.id == setup.salesUserId }.groupIds == [standard]
    }

    def "the users' groups and the keys' areas are shown and left as they are when a change leaves them out"() {
        given:
        def setup = knowledge()

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/users/${setup.salesUserId}",
                [username: setup.salesUsername, displayName: "Maria Huber"])
        def key = api.call(setup.admin, "POST", "/tenant/admin/api-keys", [name: "ERP", areaIds: [setup.sales.id]]) as Map
        api.call(setup.admin, "PUT", "/tenant/admin/api-keys/${key.key.id}", [name: "ERP 2"])

        then:
        key.key.areaIds == [setup.sales.id]
        (api.call(setup.admin, "GET", "/tenant/admin/users") as List<Map>).find { it.id == setup.salesUserId }.groupIds == [setup.salesGroup.id]
        (api.admin("GET", "/admin/tenants/${setup.tenant.id}/api-keys") as List<Map>).find { it.id == key.key.id }.areaIds == [setup.sales.id]
    }

    def "a moved document is found by its new area at once, without being embedded again"() {
        given:
        def setup = knowledge()
        def embeddings = embeddingCalls(setup)

        when:
        def moved = api.call(setup.admin, "PUT", "/tenant/admin/documents/${setup.workshopDocument}/area",
                [areaId: setup.sales.id]) as Map

        then:
        moved.areaId == setup.sales.id
        moved.area == "Vertrieb"
        moved.status == "READY"
        sources(setup, setup.salesUser) == ["allgemein.txt", "vertrieb.txt", "werkstatt.txt"]

        when: "moved to the company knowledge, everyone finds it"
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}/documents/${setup.workshopDocument}/area", [areaId: null])

        then:
        sources(setup, setup.plainUser) == ["allgemein.txt", "werkstatt.txt"]
        embeddingCalls(setup) == embeddings + 2
    }

    def "an area holding documents is not deleted; once empty it is"() {
        given:
        def setup = knowledge()

        expect:
        api.status(setup.admin, "DELETE", "/tenant/admin/knowledge-areas/${setup.workshop.id}") == HttpStatus.CONFLICT

        when:
        api.status(setup.admin, "DELETE", "/tenant/admin/documents/${setup.workshopDocument}")

        then:
        api.status(setup.admin, "DELETE", "/tenant/admin/knowledge-areas/${setup.workshop.id}") == HttpStatus.NO_CONTENT
    }

    def "the same path imported into two areas is two documents; a duplicate counts within its area only"() {
        given:
        def setup = knowledge()
        def archive = ["preise/liste.txt": "Die Preisliste: Wartung 100 Euro".getBytes("UTF-8")]

        when:
        def intoCompany = importArchive(setup.admin, archive, null)
        def intoSales = importArchive(setup.admin, archive, setup.sales.id)
        def again = importArchive(setup.admin, archive, setup.sales.id)

        then:
        intoCompany*.outcome == ["CREATED"]
        intoSales*.outcome == ["CREATED"]
        again*.outcome == ["UNCHANGED"]
        intoCompany[0].documentId != intoSales[0].documentId
        (api.call(setup.admin, "GET", "/tenant/admin/documents") as List<Map>)
                .findAll { it.fileName == "liste.txt" }.collect { it.area }.toSet() == [null, "Vertrieb"].toSet()
    }

    def "deleting the uploads of the company knowledge or an area deletes only those - the client software's stay"() {
        given:
        def setup = knowledge()
        def key = apiKey(setup.admin, [])
        def pushed = push(key, "erp.txt", "Die Preisliste der Software", null)
        api.awaitDocument(setup.admin, pushed.id)
        def general = (api.call(setup.admin, "GET", "/tenant/admin/documents") as List<Map>).find { it.fileName == "allgemein.txt" }

        when:
        def company = api.call(setup.admin, "DELETE", "/tenant/admin/documents/uploads?area=company") as Map

        then:
        company.deleted == 1
        documentNames(setup.admin) == ["erp.txt", "vertrieb.txt", "werkstatt.txt"]
        sources(setup, setup.plainUser) == ["erp.txt"]
        chunkCount(setup, general.id) == 0
        chunkCount(setup, pushed.id) > 0

        when:
        def sales = api.admin("DELETE", "/admin/tenants/${setup.tenant.id}/documents/uploads?area=${setup.sales.id}") as Map

        then:
        sales.deleted == 1
        documentNames(setup.admin) == ["erp.txt", "werkstatt.txt"]
    }

    def "a document whose area was deleted meanwhile is found by the tenant admins only - who can move and delete it"() {
        given: "the decided race: an upload into an area being deleted keeps the area's id"
        def setup = knowledge()
        def workshopKey = apiKey(setup.admin, [setup.workshop.id])
        def tenant = tenantEntities.findById(setup.tenant.id as Long).get()
        documents.updateArea(databases.dataSource(tenant.databaseName()), setup.workshopDocument as Long, 999999999L)

        expect:
        sources(setup, setup.admin) == ["allgemein.txt", "vertrieb.txt", "werkstatt.txt"]
        sources(setup, setup.plainUser) == ["allgemein.txt"]
        listNames(workshopKey) == ["allgemein.txt"]
        documentNames(setup.admin) == ["allgemein.txt", "vertrieb.txt", "werkstatt.txt"]

        when:
        api.call(setup.admin, "PUT", "/tenant/admin/documents/${setup.workshopDocument}/area", [areaId: setup.sales.id])

        then:
        sources(setup, setup.salesUser) == ["allgemein.txt", "vertrieb.txt", "werkstatt.txt"]

        expect:
        api.status(setup.admin, "DELETE", "/tenant/admin/documents/${setup.workshopDocument}") == HttpStatus.NO_CONTENT
        chunkCount(setup, setup.workshopDocument) == 0
    }

    def "deleting uploads needs a valid area: #area"() {
        given:
        def admin = api.tenantAdminToken(newTenant().id)

        expect:
        api.status(admin, "DELETE", "/tenant/admin/documents/uploads" + area) == HttpStatus.BAD_REQUEST

        where:
        area << ["", "?area=", "?area=alle", "?area=999999999"]
    }

    def "an upload into an area is shown with it; an unknown area is refused"() {
        given:
        def setup = knowledge()

        expect:
        (api.call(setup.admin, "GET", "/tenant/admin/documents") as List<Map>).find { it.fileName == "vertrieb.txt" }
                .subMap(["areaId", "area"]) == [areaId: setup.sales.id, area: "Vertrieb"]
        uploadStatus(setup.admin, "b.txt", "Text", 999999999) == HttpStatus.BAD_REQUEST
        api.status(setup.admin, "PUT", "/tenant/admin/documents/${setup.workshopDocument}/area", [areaId: 999999999]) == HttpStatus.BAD_REQUEST
        api.status(setup.admin, "PUT", "/tenant/admin/documents/999999999/area", [areaId: null]) == HttpStatus.NOT_FOUND
    }

    def "the client software finds the company knowledge and its key's areas - in the chat, with or without history"() {
        given:
        def setup = knowledge()
        def plain = apiKey(setup.admin, [])
        def sales = apiKey(setup.admin, [setup.sales.id])
        def forgetting = (api.call(setup.admin, "POST", "/tenant/admin/api-keys",
                [name: "Ohne Verlauf", keepsHistory: false, areaIds: [setup.workshop.id]]) as Map).plainKey as String

        expect:
        apiSources(plain, setup) == ["allgemein.txt"]
        apiSources(sales, setup) == ["allgemein.txt", "vertrieb.txt"]
        apiSources(forgetting, setup) == ["allgemein.txt", "werkstatt.txt"]
        api.call(sales, "GET", "/api/v1/me").areas == ["Vertrieb"]
    }

    def "the client software lists, pushes into and deletes only what its key finds"() {
        given:
        def setup = knowledge()
        def plain = apiKey(setup.admin, [])
        def sales = apiKey(setup.admin, [setup.sales.id])

        expect:
        listNames(plain) == ["allgemein.txt"]
        listNames(sales) == ["allgemein.txt", "vertrieb.txt"]
        api.status(plain, "DELETE", "/api/v1/documents/${setup.workshopDocument}") == HttpStatus.NOT_FOUND
        pushStatus(plain, "x.txt", "Text", "Vertrieb") == HttpStatus.BAD_REQUEST
        pushStatus(sales, "x.txt", "Text", "Unbekannt") == HttpStatus.BAD_REQUEST

        when: "a push into its area, by the area's name in any case"
        def pushed = push(sales, "angebot.txt", "Das Angebot", "vertrieb", "angebot-1")

        then:
        pushed.area == "Vertrieb"
        listNames(plain) == ["allgemein.txt"]

        and: "another key may not replace or delete it"
        pushStatus(plain, "angebot.txt", "Das neue Angebot", null, "angebot-1") == HttpStatus.CONFLICT
        api.status(plain, "DELETE", "/api/v1/documents?externalId=angebot-1") == HttpStatus.NOT_FOUND
    }

    def "a known document pushed unchanged with another area moves there without being embedded again"() {
        given:
        def setup = knowledge()
        def sales = apiKey(setup.admin, [setup.sales.id])
        def pushed = push(sales, "angebot.txt", "Das Angebot", "Vertrieb", "angebot-1")
        api.awaitDocument(setup.admin, pushed.id)
        def embeddings = embeddingCalls(setup)

        when: "pushed with an empty area - the company knowledge"
        def moved = push(sales, "angebot.txt", "Das Angebot", "", "angebot-1")

        then:
        moved.id == pushed.id
        moved.areaId == null
        moved.status == "READY"
        embeddingCalls(setup) == embeddings
    }

    def "a known document pushed without an area keeps the area it is in - also one a tenant admin moved it to"() {
        given:
        def setup = knowledge()
        def sales = apiKey(setup.admin, [setup.sales.id])
        def pushed = push(sales, "angebot.txt", "Das Angebot", null, "angebot-1")
        api.awaitDocument(setup.admin, pushed.id)
        api.call(setup.admin, "PUT", "/tenant/admin/documents/${pushed.id}/area", [areaId: setup.sales.id])

        when:
        def unchanged = push(sales, "angebot.txt", "Das Angebot", null, "angebot-1")
        def replaced = push(sales, "angebot.txt", "Das neue Angebot", null, "angebot-1")

        then:
        pushed.areaId == null
        unchanged.areaId == setup.sales.id
        replaced.areaId == setup.sales.id
        replaced.id == pushed.id
    }

    def "a document moved into an area its key is not assigned to is neither replaced nor moved by the key"() {
        given:
        def setup = knowledge()
        def plain = apiKey(setup.admin, [])
        def pushed = push(plain, "angebot.txt", "Das Angebot", null, "angebot-1")
        api.awaitDocument(setup.admin, pushed.id)
        api.call(setup.admin, "PUT", "/tenant/admin/documents/${pushed.id}/area", [areaId: setup.workshop.id])

        expect:
        pushStatus(plain, "angebot.txt", "Das neue Angebot", null, "angebot-1") == HttpStatus.CONFLICT
        pushStatus(plain, "angebot.txt", "Das Angebot", "", "angebot-1") == HttpStatus.CONFLICT
        (api.call(setup.admin, "GET", "/tenant/admin/documents") as List<Map>).find { it.id == pushed.id }
                .subMap(["areaId", "status"]) == [areaId: setup.workshop.id, status: "READY"]
    }

    def "moving a ZIP archive's file into an area that has the same path already is refused"() {
        given:
        def setup = knowledge()
        def archive = ["preise/liste.txt": "Die Preisliste: Wartung 100 Euro".getBytes("UTF-8")]
        def inCompany = importArchive(setup.admin, archive, null)[0].documentId
        importArchive(setup.admin, archive, setup.sales.id)

        expect:
        api.status(setup.admin, "PUT", "/tenant/admin/documents/${inCompany}/area", [areaId: setup.sales.id]) == HttpStatus.CONFLICT
        api.error(setup.admin, "PUT", "/tenant/admin/documents/${inCompany}/area", [areaId: setup.sales.id])
                .contains("same path")
    }

    def "a document waiting to be embedded again is found by no one - its old chunks may belong to another area"() {
        given:
        def setup = knowledge()
        def tenant = tenantEntities.findById(setup.tenant.id as Long).get()
        def general = (api.call(setup.admin, "GET", "/tenant/admin/documents") as List<Map>).find { it.fileName == "allgemein.txt" }

        when:
        documents.updateStatus(databases.dataSource(tenant.databaseName()), general.id as Long, DocumentStatus.PENDING, null, 0)

        then:
        sources(setup, setup.plainUser) == []
        sources(setup, setup.admin) == ["vertrieb.txt", "werkstatt.txt"]
    }

    def "the client software's conversations are its key's: another key neither lists, reads nor continues them"() {
        given:
        def setup = knowledge()
        def salesKey = apiKey(setup.admin, [setup.sales.id])
        def plainKey = apiKey(setup.admin, [])
        def asked = api.call(salesKey, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: QUESTION]) as Map
        def conversationId = asked.conversation.id
        def fileId = api.chatUpload(salesKey, ["notiz.txt": "Hallo".bytes], conversationId, "/api/v1/chat/files").files[0].id

        expect:
        (api.call(salesKey, "GET", "/api/v1/chat/conversations") as List<Map>)*.id == [conversationId]
        api.call(plainKey, "GET", "/api/v1/chat/conversations") == []
        api.status(plainKey, "GET", "/api/v1/chat/conversations/${conversationId}/messages") == HttpStatus.NOT_FOUND
        api.status(plainKey, "GET", "/api/v1/chat/conversations/${conversationId}/files") == HttpStatus.NOT_FOUND
        api.status(plainKey, "POST", "/api/v1/chat", [conversationId: conversationId, modelId: setup.chat.id,
                                                       message: "Und weiter?"]) == HttpStatus.NOT_FOUND
        api.status(plainKey, "DELETE", "/api/v1/chat/conversations/${conversationId}") == HttpStatus.NOT_FOUND

        and: "nor its files"
        api.status(plainKey, "GET", "/api/v1/chat/files/${fileId}/content") == HttpStatus.NOT_FOUND
        api.status(plainKey, "DELETE", "/api/v1/chat/files/${fileId}") == HttpStatus.NOT_FOUND
        api.chatUploadStatus(plainKey, ["x.txt": "x".bytes], conversationId, "/api/v1/chat/files") == HttpStatus.NOT_FOUND
        api.status(salesKey, "GET", "/api/v1/chat/files/${fileId}/content") == HttpStatus.OK
    }

    def "revoking an API key deletes its conversations"() {
        given:
        def setup = knowledge()
        def created = api.call(setup.admin, "POST", "/tenant/admin/api-keys", [name: "ERP"]) as Map
        api.call(created.plainKey as String, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: QUESTION])
        def tenant = tenantEntities.findById(setup.tenant.id as Long).get()
        def owner = ConversationOwner.apiKey(created.key.id as Long)
        def dataSource = databases.dataSource(tenant.databaseName())

        expect:
        conversations.findByOwner(dataSource, owner).size() == 1

        when:
        api.status(setup.admin, "DELETE", "/tenant/admin/api-keys/${created.key.id}")

        then:
        conversations.findByOwner(dataSource, owner) == []
    }

    def "at /a2a a key finds the company knowledge and its own areas - as at /mcp, never another key's"() {
        given:
        def setup = knowledge()
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: "DOCUMENTS_ONLY",
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5, defaultModelId: setup.chat.id])
        def plain = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Allgemein A2A", a2a: true]) as Map
        def sales = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys",
                [name: "Vertrieb A2A", a2a: true, areaIds: [setup.sales.id]]) as Map

        when:
        String forPlain = a2aAnswer(plain.plainKey)
        String forSales = a2aAnswer(sales.plainKey)

        then:
        forPlain.contains("allgemein.txt")
        !forPlain.contains("vertrieb.txt") && !forPlain.contains("werkstatt.txt")
        forSales.contains("allgemein.txt") && forSales.contains("vertrieb.txt")
        !forSales.contains("werkstatt.txt")
    }

    def "each MCP key finds the company knowledge and its own areas - kept when the key is renewed"() {
        given:
        def setup = knowledge()
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", [instructions: "", answerSource: "DOCUMENTS_ONLY",
                creativity: "MEDIUM", reasoning: "LOW", searchResults: 5, defaultModelId: setup.chat.id])
        def plain = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Allgemein"]) as Map
        def sales = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys", [name: "Vertrieb", areaIds: [setup.sales.id]]) as Map

        expect: "each key its own areas"
        sales.key.areaIds == [setup.sales.id]
        mcpSources(plain.plainKey) == "allgemein.txt"
        mcpSources(sales.plainKey) == "allgemein.txt, vertrieb.txt"

        when: "the admin gives the plain key the workshop, then it is renewed"
        def assigned = api.admin("PUT", "/admin/tenants/${setup.tenant.id}/mcp-keys/${plain.key.id}",
                [areaIds: [setup.workshop.id]]) as Map
        def renewed = api.call(setup.admin, "POST", "/tenant/admin/mcp-keys/${plain.key.id}/renew") as Map

        then:
        assigned.areaIds == [setup.workshop.id]
        renewed.key.areaIds == [setup.workshop.id]
        mcpSources(renewed.plainKey) == "allgemein.txt, werkstatt.txt"
        mcpSources(sales.plainKey) == "allgemein.txt, vertrieb.txt"

        when: "an area of the key is deleted"
        def empty = api.call(setup.admin, "POST", "/tenant/admin/knowledge-areas", [name: "Leer"]) as Map
        api.call(setup.admin, "PUT", "/tenant/admin/mcp-keys/${plain.key.id}", [areaIds: [setup.workshop.id, empty.id]])
        def apiKey = api.call(setup.admin, "POST", "/tenant/admin/api-keys", [name: "ERP", areaIds: [empty.id]]) as Map
        def deleted = api.status(setup.admin, "DELETE", "/tenant/admin/knowledge-areas/${empty.id}")

        then: "it is gone from the key - and from an API key"
        deleted == HttpStatus.NO_CONTENT
        (api.call(setup.admin, "GET", "/tenant/admin/mcp-keys") as List<Map>).find { it.id == plain.key.id }.areaIds ==
                [setup.workshop.id]
        (api.call(setup.admin, "GET", "/tenant/admin/api-keys") as List<Map>).find { it.id == apiKey.key.id }.areaIds == []
    }

    def "a user reaches none of the areas' admin API"() {
        given:
        def tenant = newTenant()
        def user = api.userToken(tenant.id)

        expect:
        api.status(user, "GET", "/tenant/admin/knowledge-areas") == HttpStatus.FORBIDDEN
        api.status(user, "POST", "/tenant/admin/knowledge-areas", [name: "X"]) == HttpStatus.FORBIDDEN
        api.status(user, "DELETE", "/tenant/admin/documents/uploads?area=company") == HttpStatus.FORBIDDEN
        api.status(user, "POST", "/tenant/admin/mcp-keys", [name: "X", areaIds: []]) == HttpStatus.FORBIDDEN
        api.status(user, "GET", "/admin/tenants/${tenant.id}/knowledge-areas") == HttpStatus.FORBIDDEN
    }

    /**
     * A tenant with a chat model and three documents answering QUESTION: the company knowledge's, the area "Vertrieb"'s
     * and the area "Werkstatt"'s; a user in no group and one in a group with the area "Vertrieb".
     */
    private Map knowledge() {
        def tenant = newTenant()
        def admin = userWithEmbedding(tenant)
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        def sales = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def workshop = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Werkstatt"]) as Map
        api.awaitDocument(admin, api.upload(admin, "allgemein.txt", "Die Preisliste für alle: Wartung 100 Euro").id)
        api.awaitDocument(admin, upload(admin, "vertrieb.txt", "Die Preisliste des Vertriebs: Rabatt 20 Prozent", sales.id).id)
        def workshopDocument = upload(admin, "werkstatt.txt", "Die Preisliste der Werkstatt: Stunde 80 Euro", workshop.id).id
        api.awaitDocument(admin, workshopDocument)
        def salesGroup = api.group(admin, [], [sales.id])
        def salesUsername = "vertrieb-" + AiApi.unique()
        def salesUser = api.call(admin, "POST", "/tenant/admin/users", [username: salesUsername, displayName: "Maria",
                                                                       password: AiApi.USER_PASSWORD, groupIds: [salesGroup.id]]) as Map
        [tenant       : tenant, admin: admin, chat: chat, sales: sales, workshop: workshop, workshopDocument: workshopDocument,
         plainUser    : api.userToken(tenant.id), salesUserId: salesUser.id, salesUsername: salesUsername,
         salesUser    : login(salesUsername), salesGroup: salesGroup]
    }

    /** The tenant's admin token, with the OpenAI key and an embedding model. */
    private String userWithEmbedding(Map tenant) {
        def admin = api.tenantAdminToken(tenant.id)
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        admin
    }

    private String login(String username) {
        client.toBlocking().retrieve(HttpRequest.POST("/login", [username: username, password: AiApi.USER_PASSWORD]), Map).access_token
    }

    /** The documents the answer to QUESTION drew on, in the chat of this user. */
    private List<String> sources(Map setup, String token) {
        (api.ask(token, [modelId: setup.chat.id, message: QUESTION]).sources as List<String>).sort()
    }

    private List<String> apiSources(String key, Map setup) {
        (api.call(key, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: QUESTION]).sources as List<String>).sort()
    }

    /** The whole answer of a plain question at /a2a - its text and sources. */
    private String a2aAnswer(String key) {
        client.toBlocking().retrieve(HttpRequest.POST("/a2a", [jsonrpc: "2.0", id: 1, method: "SendMessage",
                params: [message: [role: "ROLE_USER", messageId: UUID.randomUUID().toString(), parts: [[text: QUESTION]]]]])
                .header("A2A-Version", "1.0").bearerAuth(key), String)
    }

    private String mcpSources(String key) {
        def response = client.toBlocking().exchange(HttpRequest.POST("/mcp", [jsonrpc: "2.0", id: AiApi.unique(),
                method: "tools/call", params: [name: "ask_assistant", arguments: [question: QUESTION]]]).bearerAuth(key), Map)
        String text = response.body().result.content[0].text
        text.substring(text.indexOf("Sources: ") + "Sources: ".length()).split(", ").sort().join(", ")
    }

    private String mcpKey(String admin) {
        (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey
    }

    private String apiKey(String admin, List areaIds) {
        (api.call(admin, "POST", "/tenant/admin/api-keys", [name: "Software " + AiApi.unique(), areaIds: areaIds]) as Map).plainKey
    }

    private int embeddingCalls(Map setup) {
        def usage = (api.call(setup.admin, "GET", "/tenant/admin/usage") as List<Map>).find { it.kind == "EMBEDDING" }
        usage == null ? 0 : usage.calls as int
    }

    private int chunkCount(Map setup, Object documentId) {
        def databaseName = tenantEntities.findById(setup.tenant.id as Long).get().databaseName()
        databases.dataSource(databaseName).connection.withCloseable { connection ->
            def result = connection.createStatement()
                    .executeQuery("SELECT COUNT(*) FROM embedding WHERE metadata->>'document_id' = '${documentId}'")
            result.next()
            result.getInt(1)
        }
    }

    private List<String> documentNames(String admin) {
        (api.call(admin, "GET", "/tenant/admin/documents") as List<Map>).collect { it.fileName as String }.sort()
    }

    private Map upload(String token, String fileName, String text, Object areaId) {
        client.toBlocking().retrieve(uploadRequest(token, fileName, text, areaId), Map)
    }

    private HttpStatus uploadStatus(String token, String fileName, String text, Object areaId) {
        try {
            client.toBlocking().exchange(uploadRequest(token, fileName, text, areaId), Map).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    private static HttpRequest<?> uploadRequest(String token, String fileName, String text, Object areaId) {
        def body = MultipartBody.builder().addPart("file", fileName, MediaType.TEXT_PLAIN_TYPE, text.getBytes("UTF-8"))
                .addPart("areaId", areaId.toString()).build()
        HttpRequest.POST("/tenant/admin/documents", body).contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token)
    }

    private List<Map> importArchive(String token, Map<String, byte[]> entries, Object areaId) {
        def body = MultipartBody.builder().addPart("file", "wissen.zip", MediaType.APPLICATION_OCTET_STREAM_TYPE, AiApi.zip(entries))
        if (areaId != null) {
            body.addPart("areaId", areaId.toString())
        }
        client.toBlocking().retrieve(HttpRequest.POST("/tenant/admin/documents/archive", body.build())
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token), Map).files as List<Map>
    }

    private Map push(String key, String fileName, String text, String area, String externalId = null) {
        client.toBlocking().retrieve(pushRequest(key, fileName, text, area, externalId), Map)
    }

    private HttpStatus pushStatus(String key, String fileName, String text, String area, String externalId = null) {
        try {
            client.toBlocking().exchange(pushRequest(key, fileName, text, area, externalId), Map).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    private static HttpRequest<?> pushRequest(String key, String fileName, String text, String area, String externalId) {
        def body = MultipartBody.builder().addPart("file", fileName, MediaType.APPLICATION_OCTET_STREAM_TYPE, text.getBytes("UTF-8"))
        if (area != null) {
            body.addPart("area", area)
        }
        if (externalId != null) {
            body.addPart("externalId", externalId)
        }
        HttpRequest.POST("/api/v1/documents", body.build()).contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(key)
    }

    private List<String> listNames(String key) {
        client.toBlocking().retrieve(HttpRequest.GET("/api/v1/documents").bearerAuth(key), Argument.listOf(Map))
                .collect { it.fileName as String }.sort()
    }
}
