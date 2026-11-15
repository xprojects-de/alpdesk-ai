package de.alpdesk.controller.assistant

import de.alpdesk.entities.assistant.Creativity
import de.alpdesk.entities.assistant.Reasoning
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.assistant.AssistantService
import dev.langchain4j.data.message.SystemMessage
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * How a tenant's assistant answers: its settings (instructions, answer source, creativity, reasoning, search results)
 * and its templates, kept by its tenant admins and the admin, chosen by its users and the client software - and what
 * reaches the model of it.
 */
@MicronautTest(transactional = false)
class AssistantSpec extends Specification {

    static final Map SETTINGS = [instructions : "Du unterstützt die Trainer des Vereins.", answerSource: "DOCUMENTS_FIRST",
                                 creativity   : "HIGH", reasoning: "MEDIUM", searchResults: 8, defaultModelId: null,
                                 imageModelId : null]

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    AssistantService assistants

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "a new tenant has the defaults; its tenant admin and the admin keep the settings"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)

        expect:
        api.call(token, "GET", "/tenant/admin/assistant") ==
                [instructions: "", answerSource: "DOCUMENTS_ONLY", creativity: "MEDIUM", reasoning: "LOW", searchResults: 5,
                 defaultModelId: null, imageModelId: null]

        when:
        def saved = api.call(token, "PUT", "/tenant/admin/assistant", SETTINGS + [instructions: "  Du unterstützt die Trainer des Vereins.  "])

        then:
        saved == SETTINGS
        api.admin("GET", "/admin/tenants/${tenant.id}/assistant") == SETTINGS

        when:
        api.admin("PUT", "/admin/tenants/${tenant.id}/assistant", SETTINGS + [creativity: "LOW"])

        then:
        (api.call(token, "GET", "/tenant/admin/assistant") as Map).creativity == "LOW"
    }

    def "invalid settings are refused: #reason"() {
        given:
        def token = api.tenantAdminToken(api.tenant().id)

        expect:
        api.status(token, "PUT", "/tenant/admin/assistant", SETTINGS + changes) == HttpStatus.BAD_REQUEST

        where:
        reason                        | changes
        "no answer source"            | [answerSource: null]
        "no creativity"               | [creativity: null]
        "no reasoning"                | [reasoning: null]
        "no search results"           | [searchResults: null]
        "too few search results"      | [searchResults: 2]
        "too many search results"     | [searchResults: 21]
        "instructions too long"       | [instructions: "x" * 4001]
        "an unknown creativity"       | [creativity: "EXTREME"]
    }

    def "the chat's image model: the only one the tenant may use without choosing, else the chosen one"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        def first = api.imageModel()
        def second = api.imageModel()
        def chat = api.chatModel("GEMINI")

        expect: "none while the tenant may use none"
        assistants.imageModel(tenant.id).empty

        when: "the admin allows exactly one"
        api.allow(tenant.id, chat, first)

        then: "it is used without being chosen; the choice lists only image models"
        assistants.imageModel(tenant.id).get().id() == first.id
        (api.call(token, "GET", "/tenant/admin/assistant/image-models") as List)*.id == [first.id]
        (api.admin("GET", "/admin/tenants/${tenant.id}/assistant/image-models") as List)*.id == [first.id]

        when: "the admin allows a second one"
        api.allow(tenant.id, second)

        then: "none until one is chosen"
        assistants.imageModel(tenant.id).empty

        when:
        def saved = api.call(token, "PUT", "/tenant/admin/assistant", SETTINGS + [imageModelId: second.id]) as Map

        then:
        saved.imageModelId == second.id
        assistants.imageModel(tenant.id).get().id() == second.id

        when: "the admin takes the chosen one away"
        api.admin("PUT", "/admin/tenants/${tenant.id}/models", [modelIds: [chat.id, first.id]])

        then: "the only one left is used, and the settings no longer name the one taken away"
        assistants.imageModel(tenant.id).get().id() == first.id
        def shown = api.call(token, "GET", "/tenant/admin/assistant") as Map
        shown.imageModelId == null

        and: "they can be saved as shown"
        (api.call(token, "PUT", "/tenant/admin/assistant", shown + [instructions: "Neu"]) as Map).imageModelId == null
    }

    def "a chosen image model the tenant can no longer use never stops the settings from being saved"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel("GEMINI")
        def image = api.imageModel()
        api.allow(tenant.id, chat, image)
        api.call(token, "PUT", "/tenant/admin/assistant", SETTINGS + [imageModelId: image.id])

        when: "the admin takes away every image model"
        api.admin("PUT", "/admin/tenants/${tenant.id}/models", [modelIds: [chat.id]])
        def shown = api.admin("GET", "/admin/tenants/${tenant.id}/assistant") as Map

        then:
        shown.imageModelId == null
        api.status(token, "PUT", "/tenant/admin/assistant", shown + [instructions: "Neu"]) == HttpStatus.OK
        assistants.imageModel(tenant.id).empty
    }

    def "a default model the tenant can no longer use never stops the settings from being saved"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)
        def kept = api.chatModel()
        def takenAway = api.chatModel()
        api.allow(tenant.id, kept, takenAway)
        api.call(token, "PUT", "/tenant/admin/assistant", SETTINGS + [defaultModelId: takenAway.id])

        when: "the admin takes the default model away"
        api.admin("PUT", "/admin/tenants/${tenant.id}/models", [modelIds: [kept.id]])
        def shown = api.call(token, "GET", "/tenant/admin/assistant") as Map

        then: "the settings no longer name it and can be saved as shown"
        shown.defaultModelId == null
        api.status(token, "PUT", "/tenant/admin/assistant", shown + [instructions: "Neu"]) == HttpStatus.OK
    }

    def "only one of the tenant's image models can be chosen: #reason"() {
        given:
        def tenant = api.tenant()
        def chat = api.chatModel("GEMINI")
        def image = api.imageModel()
        api.allow(tenant.id, chat, image)
        def models = [chat: chat.id, notAssigned: api.imageModel().id, unknown: Long.MAX_VALUE]

        expect:
        api.status(api.tenantAdminToken(tenant.id), "PUT", "/tenant/admin/assistant",
                SETTINGS + [imageModelId: models[model]]) == HttpStatus.BAD_REQUEST

        where:
        reason                         | model
        "a chat model"                 | "chat"
        "an image model not assigned"  | "notAssigned"
        "an unknown model"             | "unknown"
    }

    def "templates are kept by the tenant admin and the admin; names are unique per tenant"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)

        when:
        def planner = api.call(token, "POST", "/tenant/admin/assistant/templates",
                [name       : " Angebotsprüfung ", instructions: "Prüfe Angebote auf Preise und Fristen.", reasoning: "HIGH",
                 description: "  Prüft ein Angebot\n  gegen die Lieferbedingungen. "]) as Map
        def lookup = api.admin("POST", "/admin/tenants/${tenant.id}/assistant/templates",
                [name: "Nachschlagen", answerSource: "DOCUMENTS_ONLY", creativity: "LOW", searchResults: 3]) as Map

        then:
        planner.name == "Angebotsprüfung"
        planner.description == "Prüft ein Angebot gegen die Lieferbedingungen."
        planner.reasoning == "HIGH"
        planner.creativity == null
        (api.call(token, "GET", "/tenant/admin/assistant/templates") as List)*.name == ["Angebotsprüfung", "Nachschlagen"]

        expect:
        api.status(token, "POST", "/tenant/admin/assistant/templates", [name: "angebotsprüfung"]) == HttpStatus.CONFLICT
        api.status(api.tenantAdminToken(api.tenant().id), "POST", "/tenant/admin/assistant/templates",
                [name: "Angebotsprüfung"]) == HttpStatus.CREATED

        when:
        def changed = api.call(token, "PUT", "/tenant/admin/assistant/templates/${planner.id}",
                [name: "Planer", instructions: "Kurz.", creativity: "HIGH"]) as Map

        then:
        changed.name == "Planer"
        changed.description == null
        changed.reasoning == null
        changed.creativity == "HIGH"

        when:
        def deleted = api.status(api.adminToken, "DELETE", "/admin/tenants/${tenant.id}/assistant/templates/${lookup.id}")

        then:
        deleted == HttpStatus.NO_CONTENT
        (api.call(token, "GET", "/tenant/admin/assistant/templates") as List)*.name == ["Planer"]
    }

    def "invalid templates are refused: #reason"() {
        given:
        def token = api.tenantAdminToken(api.tenant().id)

        expect:
        api.status(token, "POST", "/tenant/admin/assistant/templates", [name: "Vorlage"] + changes) == HttpStatus.BAD_REQUEST

        where:
        reason                    | changes
        "no name"                 | [name: null]
        "a blank name"            | [name: "  "]
        "a name too long"         | [name: "x" * 81]
        "instructions too long"   | [instructions: "x" * 4001]
        "a description too long"  | [description: "x" * 501]
        "too few search results"  | [searchResults: 2]
        "too many search results" | [searchResults: 21]
    }

    def "another tenant's templates are not found"() {
        given:
        def owner = api.tenant()
        def template = api.call(api.tenantAdminToken(owner.id), "POST", "/tenant/admin/assistant/templates", [name: "Eigene"]) as Map
        def other = api.tenantAdminToken(api.tenant().id)

        expect:
        api.status(other, "PUT", "/tenant/admin/assistant/templates/${template.id}", [name: "Fremd"]) == HttpStatus.NOT_FOUND
        api.status(other, "DELETE", "/tenant/admin/assistant/templates/${template.id}") == HttpStatus.NOT_FOUND
        api.call(other, "GET", "/tenant/admin/assistant/templates") == []
        api.status(api.adminToken, "GET", "/admin/tenants/999999/assistant") == HttpStatus.NOT_FOUND
    }

    def "users and the client software see the templates' names and descriptions, never their instructions"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def template = api.call(admin, "POST", "/tenant/admin/assistant/templates",
                [name: "Planer", instructions: "Geheim.", description: "Plant die Woche."]) as Map
        def bare = api.call(admin, "POST", "/tenant/admin/assistant/templates", [name: "Rohentwurf", description: "  "]) as Map
        def key = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).plainKey as String
        def listed = [[id: template.id, name: "Planer", description: "Plant die Woche."], [id: bare.id, name: "Rohentwurf", description: null]]

        expect:
        api.call(api.userToken(tenant.id), "GET", "/tenant/chat/templates") == listed
        api.call(key, "GET", "/api/v1/templates") == listed
        api.status(api.userToken(tenant.id), "GET", "/tenant/admin/assistant") == HttpStatus.FORBIDDEN
    }

    def "the tenant's instructions always reach the model, a template's next to them; the rules come first"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", SETTINGS + [answerSource: "DOCUMENTS_ONLY"])
        def template = api.call(setup.admin, "POST", "/tenant/admin/assistant/templates",
                [name: "Planer", instructions: "Antworte als Wochenplan.", answerSource: "DOCUMENTS_FIRST"]) as Map

        when:
        api.ask(setup.user, [modelId: setup.chat.id, message: "Wie plane ich die Woche?"])
        def plain = systemPrompt()
        api.ask(setup.user, [modelId: setup.chat.id, message: "Wie plane ich die Woche?", templateId: template.id])
        def withTemplate = systemPrompt()

        then:
        plain.contains("Answer only from what the documents say")
        plain.contains("never that something does not exist or is not documented")
        plain.contains("Names, values, codes and settings that are not in the passages are never written as if documented")
        plain.contains("another product - is named as a conclusion")
        plain.contains("name it in brackets - its file name only")
        plain.contains("=== The company's instructions ===")
        plain.contains("Du unterstützt die Trainer des Vereins.")
        !plain.contains("Antworte als Wochenplan.")
        plain.indexOf("similarity_search first") < plain.indexOf("Du unterstützt")

        and: "the template's answer source wins, the tenant's instructions stay"
        withTemplate.contains("you may add general")
        withTemplate.contains('"General knowledge:"')
        withTemplate.contains("never that something does not exist or is not documented")
        withTemplate.contains("another product - is named as a conclusion")
        withTemplate.contains("Du unterstützt die Trainer des Vereins.")
        withTemplate.contains("=== The instructions of the template \"Planer\" ===")
        withTemplate.indexOf("Du unterstützt") < withTemplate.indexOf("Antworte als Wochenplan.")
    }

    def "without documents to search there is no rule about what a search did not find"() {
        given: "a tenant without an embedding model"
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        api.allow(tenant.id, chat)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])

        when:
        api.ask(api.userToken(tenant.id), [modelId: chat.id, message: "Wie plane ich die Woche?"])

        then:
        systemPrompt().contains("The company has no documents to search")
        !systemPrompt().contains("never that something does not exist")
        !systemPrompt().contains("is named as a conclusion")
    }

    def "the rules give their examples in English and keep the answer in the question's language, whatever language the rest has"() {
        given: "German instructions, as a German company writes them"
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant",
                SETTINGS + [answerSource: "DOCUMENTS_FIRST", instructions: "Du unterstützt die Trainer des Vereins."])

        when:
        api.ask(setup.user, [modelId: setup.chat.id, message: "Which parts do I need for the oil change?"])
        def prompt = systemPrompt()

        then: "a German example word made models answer an English question in German (seen with GPT-5, October 2026)"
        prompt.contains("Answer in the language of the question - also when the")
        prompt.contains("documents, the files or these instructions are in another language.")
        prompt.contains('"(Source: Manual.pdf)"')
        prompt.contains('"General knowledge:"')
        !prompt.contains("Quelle")
        !prompt.contains("Allgemeinwissen")
        !prompt.contains("Handbuch")
    }

    def "creativity and reasoning go to the model - the template's where it has them"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        api.call(setup.admin, "PUT", "/tenant/admin/assistant", SETTINGS)
        def template = api.call(setup.admin, "POST", "/tenant/admin/assistant/templates", [name: "Genau", creativity: "LOW"]) as Map

        when:
        api.ask(setup.user, [modelId: setup.chat.id, message: "Frage"])
        def tenantOptions = fake.chats.last().options
        api.ask(setup.user, [modelId: setup.chat.id, message: "Frage", templateId: template.id])
        def templateOptions = fake.chats.last().options

        then:
        tenantOptions.creativity() == Creativity.HIGH
        tenantOptions.reasoning() == Reasoning.MEDIUM
        templateOptions.creativity() == Creativity.LOW
        templateOptions.reasoning() == Reasoning.MEDIUM
    }

    def "a search returns as many passages as the settings say"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        7.times { api.awaitDocument(setup.admin, api.upload(setup.admin, "regel-${it}.txt", "Regel ${it} zum Aufwärmen vor dem Training.").id) }
        def few = api.call(setup.admin, "POST", "/tenant/admin/assistant/templates", [name: "Kurz", searchResults: 3]) as Map

        when:
        def standard = api.ask(setup.user, [modelId: setup.chat.id, message: "Aufwärmen vor dem Training"])
        def short_ = api.ask(setup.user, [modelId: setup.chat.id, message: "Aufwärmen vor dem Training", templateId: few.id])

        then:
        standard.answer.content.endsWith("(Auszüge: 5)")
        short_.answer.content.endsWith("(Auszüge: 3)")
    }

    def "the conversation remembers its last template; an unknown or another tenant's template is refused"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        def template = api.call(setup.admin, "POST", "/tenant/admin/assistant/templates", [name: "Planer"]) as Map
        def foreign = api.call(api.tenantAdminToken(api.tenant().id), "POST", "/tenant/admin/assistant/templates", [name: "Fremd"]) as Map

        when:
        def first = api.ask(setup.user, [modelId: setup.chat.id, message: "Frage", templateId: template.id])

        then:
        first.conversation.templateId == template.id
        (api.call(setup.user, "GET", "/tenant/chat/conversations") as List)*.templateId == [template.id]

        when:
        def second = api.ask(setup.user, [conversationId: first.conversation.id, modelId: setup.chat.id, message: "Und?"])

        then:
        second.conversation.templateId == null

        expect:
        def key = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).plainKey as String
        api.error(key, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "x", templateId: foreign.id]) == "Unknown template"
        api.status(key, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: "x", templateId: 999999]) == HttpStatus.BAD_REQUEST
    }

    def "a template's description is for whoever chooses it - it never reaches the model"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        def template = api.call(setup.admin, "POST", "/tenant/admin/assistant/templates",
                [name: "Planer", instructions: "Antworte als Wochenplan.", description: "Plant die Woche des Teams."]) as Map

        when:
        api.ask(setup.user, [modelId: setup.chat.id, message: "Frage", templateId: template.id])

        then:
        systemPrompt().contains("Antworte als Wochenplan.")
        !fake.chats.last().messages.toString().contains("Plant die Woche des Teams.")
    }

    def "the client software asks with a template"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        def template = api.call(setup.admin, "POST", "/tenant/admin/assistant/templates",
                [name: "Planer", instructions: "Antworte als Wochenplan."]) as Map
        def key = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).plainKey as String

        when:
        def answer = client.toBlocking().retrieve(HttpRequest.POST("/api/v1/chat",
                [modelId: setup.chat.id, message: "Frage", templateId: template.id]).bearerAuth(key), Map)

        then:
        answer.conversation.templateId == template.id
        systemPrompt().contains("Antworte als Wochenplan.")
    }

    def "a template needs no question: sent empty, the template's name is the question"() {
        given:
        def tenant = api.tenant()
        def setup = chatSetup(tenant)
        def template = api.call(setup.admin, "POST", "/tenant/admin/assistant/templates",
                [name: "Videoanalyse", instructions: "Bewerte das Video dieser Unterhaltung."]) as Map
        def key = api.admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).plainKey as String

        when: "a user sends nothing but the template"
        def answer = api.ask(setup.user, [modelId: setup.chat.id, message: "", templateId: template.id])
        def messages = api.call(setup.user, "GET", "/tenant/chat/conversations/${answer.conversation.id}/messages") as List

        then: "the template's name is the question - stored, and answered with the template's instructions"
        messages*.content.first() == "Videoanalyse"
        answer.conversation.templateId == template.id
        systemPrompt().contains("Bewerte das Video dieser Unterhaltung.")

        and: "the client software the same, question left out altogether"
        client.toBlocking().retrieve(HttpRequest.POST("/api/v1/chat", [modelId: setup.chat.id, templateId: template.id])
                .bearerAuth(key), Map).conversation.templateId == template.id

        and: "without a template an empty question is refused as before"
        api.error(key, "POST", "/api/v1/chat", [modelId: setup.chat.id, message: " "]).startsWith("The question must have 1 to")
        api.status(setup.user, "POST", "/tenant/chat/stream", [modelId: setup.chat.id, message: ""]) == HttpStatus.BAD_REQUEST
    }

    private String systemPrompt() {
        (fake.chats.last().messages[0] as SystemMessage).text()
    }

    /** A tenant with a chat and an embedding model and keys: its tenant admin, a user, the chat model. */
    private Map chatSetup(Map tenant) {
        def admin = api.tenantAdminToken(tenant.id)
        def chat = api.chatModel()
        def embedding = api.embeddingModel()
        api.allow(tenant.id, chat, embedding)
        api.call(admin, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1"])
        api.call(admin, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        api.call(admin, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        [admin: admin, user: api.userToken(tenant.id), chat: chat]
    }
}
