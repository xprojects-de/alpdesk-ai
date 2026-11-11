package de.alpdesk.controller.ai

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/** The admin's catalog of AI models with their prices. */
@MicronautTest(transactional = false)
class AiModelSpec extends Specification {

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

    def "the admin adds, changes and deletes a model"() {
        when:
        def model = api.chatModel()

        then:
        model.kind == "CHAT"
        model.dimension == null
        model.inputPrice == 3
        model.active == true

        when:
        def updated = api.admin("PUT", "/admin/models/${model.id}", model + [displayName: "Claude", outputPrice: 20, active: false])

        then:
        updated.displayName == "Claude"
        updated.outputPrice == 20
        updated.active == false
        (api.admin("GET", "/admin/models") as List).any { it.id == model.id }

        and: "an inactive model is not offered to tenant users, although assigned"
        def tenant = api.tenant()
        api.allow(tenant.id, model)
        def token = api.tenantAdminToken(tenant.id)
        !(api.call(token, "GET", "/tenant/ai/models") as List).any { it.id == model.id }

        when:
        def status = api.status(api.adminToken, "DELETE", "/admin/models/${model.id}")

        then:
        status == HttpStatus.NO_CONTENT
        !(api.admin("GET", "/admin/models") as List).any { it.id == model.id }
    }

    def "a model keeps its price tier and cache prices - all optional, and left out on an update they are gone"() {
        when:
        def model = api.admin("POST", "/admin/models", [provider: "ANTHROPIC", kind: "CHAT", modelName: "m-" + AiApi.unique(),
                displayName: "Haiku", inputPrice: 0.1, outputPrice: 0.5, tierThreshold: 100000, tierInputPrice: 0.5,
                tierOutputPrice: 2.5, cacheReadPrice: 0.01, cacheWritePrice: 0.125, tierCacheReadPrice: 0.05,
                tierCacheWritePrice: 0.625]) as Map

        then:
        [model.tierThreshold, model.tierInputPrice, model.tierOutputPrice, model.cacheReadPrice, model.cacheWritePrice,
         model.tierCacheReadPrice, model.tierCacheWritePrice] == [100000, 0.5, 2.5, 0.01, 0.125, 0.05, 0.625]

        when: "only cache prices"
        def updated = api.admin("PUT", "/admin/models/${model.id}", model.findAll { !(it.key as String).startsWith("tier") }) as Map

        then:
        updated.tierThreshold == null
        updated.tierInputPrice == null
        updated.cacheReadPrice == 0.01

        and: "a model without any of them is as before"
        def plain = api.chatModel()
        [plain.tierThreshold, plain.cacheReadPrice, plain.cacheWritePrice] == [null, null, null]
    }

    def "invalid models are refused: #reason"() {
        expect:
        api.status(api.adminToken, "POST", "/admin/models", base + changes) == HttpStatus.BAD_REQUEST

        where:
        reason                           | changes
        "no provider"                    | [provider: null]
        "unknown provider"               | [provider: "MISTRAL"]
        "no model name"                  | [modelName: " "]
        "no display name"                | [displayName: null]
        "negative price"                 | [inputPrice: -1]
        "no price"                       | [outputPrice: null]
        "a chat model with a dimension"  | [dimension: 1536]
        "an embedding without dimension" | [kind: "EMBEDDING", provider: "OPENAI"]
        "an embedding too large"         | [kind: "EMBEDDING", provider: "OPENAI", dimension: 2001]
        "an Anthropic embedding model"   | [kind: "EMBEDDING", dimension: 1024]
        "an OpenAI image model"          | [kind: "IMAGE", provider: "OPENAI"]
        "an Anthropic image model"       | [kind: "IMAGE"]
        "an image model with dimension"  | [kind: "IMAGE", provider: "GEMINI", dimension: 8]
        "an image model reading files"   | [kind: "IMAGE", provider: "GEMINI", readsPdf: true]
        "an image model reading scans"   | [kind: "IMAGE", provider: "GEMINI", readsScans: true]
        "an image model's output limit"  | [kind: "IMAGE", provider: "GEMINI", maxOutputTokens: 4096]
        "tier prices without threshold"  | [tierInputPrice: 0.5, tierOutputPrice: 2.5]
        "a tier's cache without one"     | [tierCacheReadPrice: 0.05]
        "a threshold without its prices" | [tierThreshold: 100000]
        "a threshold with one price"     | [tierThreshold: 100000, tierInputPrice: 0.5]
        "a threshold of 0"               | [tierThreshold: 0, tierInputPrice: 0.5, tierOutputPrice: 2.5]
        "a negative cache price"         | [cacheReadPrice: -0.01]
        "a negative tier price"          | [tierThreshold: 100000, tierInputPrice: 0.5, tierOutputPrice: -1]

        base = [provider: "ANTHROPIC", kind: "CHAT", modelName: "m-" + AiApi.unique(), displayName: "M", inputPrice: 1, outputPrice: 1]
    }

    def "the admin enters a Gemini image model like any other"() {
        when:
        def model = api.imageModel()

        then:
        model.kind == "IMAGE"
        model.provider == "GEMINI"
        model.dimension == null
        model.outputPrice == 30
        (api.admin("GET", "/admin/models") as List).any { it.id == model.id && it.kind == "IMAGE" }

        and: "the same name may be a chat model too - once per kind"
        api.status(api.adminToken, "POST", "/admin/models", [provider: "GEMINI", kind: "CHAT", modelName: model.modelName,
                displayName: "Chat", inputPrice: 1, outputPrice: 1]) == HttpStatus.CREATED
        api.status(api.adminToken, "POST", "/admin/models", [provider: "GEMINI", kind: "IMAGE", modelName: model.modelName,
                displayName: "Again", inputPrice: 1, outputPrice: 1]) == HttpStatus.CONFLICT
    }

    def "what a model reads is stored; one transcriber at most, and only one that reads audio"() {
        when:
        def first = api.chatModel("GEMINI", [readsPdf: true, readsAudio: true, transcribes: true])
        def second = api.chatModel("GEMINI", [readsAudio: true, transcribes: true])
        def models = (api.admin("GET", "/admin/models") as List<Map>).collectEntries { [(it.id): it] }

        then:
        first.readsPdf && first.readsAudio && !first.containsKey("readsVideo")
        models[second.id].transcribes
        !models[first.id].transcribes

        expect: "transcribing needs audio, reading files a chat model"
        api.status(api.adminToken, "POST", "/admin/models", [provider: "GEMINI", kind: "CHAT", modelName: "t-" + AiApi.unique(),
                displayName: "T", inputPrice: 1, outputPrice: 1, transcribes: true]) == HttpStatus.BAD_REQUEST
        api.status(api.adminToken, "PUT", "/admin/models/${first.id}", first + [readsAudio: false, transcribes: true]) == HttpStatus.BAD_REQUEST
        api.status(api.adminToken, "POST", "/admin/models", [provider: "OPENAI", kind: "EMBEDDING", modelName: "e-" + AiApi.unique(),
                displayName: "E", dimension: 8, inputPrice: 1, outputPrice: 0, readsPdf: true]) == HttpStatus.BAD_REQUEST
    }

    def "a provider's model is in the catalog once per kind"() {
        given:
        def model = api.chatModel()

        expect:
        api.status(api.adminToken, "POST", "/admin/models", [provider: "ANTHROPIC", kind: "CHAT", modelName: model.modelName,
                                                            displayName: "Again", inputPrice: 1, outputPrice: 1]) == HttpStatus.CONFLICT
    }

    def "an embedding model a tenant uses keeps its dimension and cannot be deleted"() {
        given:
        def model = api.embeddingModel()
        def tenant = api.tenant()
        api.allow(tenant.id, model)
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: model.id])

        expect:
        api.status(api.adminToken, "PUT", "/admin/models/${model.id}", model + [dimension: 128]) == HttpStatus.CONFLICT
        api.status(api.adminToken, "DELETE", "/admin/models/${model.id}") == HttpStatus.CONFLICT

        and: "its prices and name can change"
        api.status(api.adminToken, "PUT", "/admin/models/${model.id}", model + [displayName: "Neu", inputPrice: 0.05]) == HttpStatus.OK
    }

    def "tenant users see the active models with whose key they would run"() {
        given:
        def anthropic = api.chatModel("ANTHROPIC")
        def openai = api.chatModel("OPENAI")
        def gemini = api.chatModel("GEMINI")
        def tenant = api.tenant(true)
        api.allow(tenant.id, anthropic, openai, gemini)
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/ANTHROPIC", [apiKey: "sk-ant-own-key-1234"])
        api.admin("PUT", "/admin/provider-keys/OPENAI", [apiKey: "sk-global-openai-5678"])
        api.status(api.adminToken, "DELETE", "/admin/provider-keys/GEMINI")

        when:
        def models = (api.call(token, "GET", "/tenant/ai/models") as List<Map>).collectEntries { [(it.id): it] }

        then:
        models[anthropic.id].keySource == "OWN"
        models[openai.id].keySource == "GLOBAL"
        models[gemini.id].keySource == null
        !models[anthropic.id].containsKey("inputPrice")
    }

    def "a model on an own server keeps its address and key - the key never goes out again"() {
        when:
        def model = serverModel("OPENAI_COMPATIBLE", [baseUrl: " https://models.example.com/v1/ ", apiKey: "sk-farm-1234567890"])

        then:
        model.baseUrl == "https://models.example.com/v1"
        model.keyHint == "…7890"
        !model.containsKey("apiKey")
        model.callsTools == true
        def listed = (api.admin("GET", "/admin/models") as List<Map>).find { it.id == model.id }
        listed.keyHint == "…7890"
        !listed.toString().contains("sk-farm")
    }

    def "a Bedrock model is kept with its region's address and its access key - the secret never goes out again"() {
        when:
        def model = serverModel("BEDROCK", BEDROCK)

        then:
        model.provider == "BEDROCK"
        model.baseUrl == "https://bedrock-runtime.eu-central-1.amazonaws.com"
        model.keyHint == "…1234"
        !model.containsKey("apiKey")
        !api.admin("GET", "/admin/models").toString().contains("geheim")
        api.status(api.adminToken, "PUT", "/admin/provider-keys/BEDROCK", [apiKey: BEDROCK.apiKey]) == HttpStatus.BAD_REQUEST
    }

    def "invalid Bedrock models are refused: #reason"() {
        expect:
        api.status(api.adminToken, "POST", "/admin/models", [provider: "BEDROCK", kind: "CHAT",
                modelName: "eu.anthropic.m-" + AiApi.unique(), displayName: "M", inputPrice: 0, outputPrice: 0] + BEDROCK
                + changes) == HttpStatus.BAD_REQUEST

        where:
        reason                                 | changes
        "no address"                           | [baseUrl: null]
        "an address without a region"          | [baseUrl: "https://models.example.com"]
        "an address that is no URL"            | [baseUrl: "https://bedrock runtime.eu-central-1.amazonaws.com"]
        "the Anthropic endpoint's address"     | [baseUrl: "https://bedrock-mantle.eu-central-1.api.aws/anthropic/v1"]
        "no key"                               | [apiKey: null]
        "a key without its secret"             | [apiKey: "AKIAEXAMPLE123456789"]
        "a Bedrock API key instead"            | [apiKey: "ABSKQmVkcm9ja0FQSUtleS1leGFtcGxl"]
        "an embedding model"                   | [kind: "EMBEDDING", dimension: 1024]
        "a context length"                     | [contextLength: 8192]
    }

    def "a Bedrock model's key stays with its region - another region needs the key again"() {
        given:
        def model = serverModel("BEDROCK", BEDROCK)
        def otherRegion = [baseUrl: "https://bedrock-runtime.eu-west-1.amazonaws.com", apiKey: null]

        expect:
        api.status(api.adminToken, "PUT", "/admin/models/${model.id}", model + [apiKey: null, displayName: "Neu"]) ==
                HttpStatus.OK
        api.status(api.adminToken, "PUT", "/admin/models/${model.id}", model + otherRegion) == HttpStatus.BAD_REQUEST
        api.status(api.adminToken, "PUT", "/admin/models/${model.id}", model + otherRegion
                + [apiKey: "AKIAEXAMPLE987654321:anderes-geheimnis-5678"]) == HttpStatus.OK
    }

    def "an own server's key stays with its host: #change"() {
        given:
        def model = serverModel("OLLAMA", [baseUrl: "https://ollama.example.com", apiKey: "proxy-key-1234567890"])

        when:
        def updated = api.admin("PUT", "/admin/models/${model.id}", model + changes)

        then:
        updated.keyHint == hint

        where:
        change                              | changes                                                                 || hint
        "another path on the same host"     | [baseUrl: "https://ollama.example.com/ollama"]                          || "…7890"
        "another host drops it"             | [baseUrl: "https://other.example.com"]                                  || null
        "another port drops it"             | [baseUrl: "https://ollama.example.com:8443"]                            || null
        "another host with a new key"       | [baseUrl: "https://other.example.com", apiKey: "new-proxy-key-0000"]    || "…0000"
        "removed"                           | [removeApiKey: true]                                                    || null
        "a new key"                         | [apiKey: "new-proxy-key-1111"]                                          || "…1111"
        "a short key shows nothing of it"   | [apiKey: "kurz"]                                                        || "…"
    }

    def "invalid own servers are refused: #reason"() {
        expect:
        api.status(api.adminToken, "POST", "/admin/models", base + changes) == HttpStatus.BAD_REQUEST

        where:
        reason                                     | changes
        "no address"                               | [baseUrl: null]
        "a blank address"                          | [baseUrl: " "]
        "plain HTTP to a host not allowed"         | [baseUrl: "http://models.example.com/v1"]
        "another scheme"                           | [baseUrl: "ftp://models.example.com"]
        "credentials in the address"               | [baseUrl: "https://user:secret@models.example.com/v1"]
        "a query"                                  | [baseUrl: "https://models.example.com/v1?key=secret"]
        "no host"                                  | [baseUrl: "https:///v1"]
        "not absolute"                             | [baseUrl: "models.example.com/v1"]
        "a key with blanks"                        | [apiKey: "sk farm key"]
        "a context length on OpenAI-compatible"    | [contextLength: 8192]
        "a context length too small"               | [provider: "OLLAMA", contextLength: 512]
        "a context length too large"               | [provider: "OLLAMA", contextLength: 2_000_000]
        "a provider's model with an address"       | [provider: "OPENAI", baseUrl: "https://models.example.com/v1"]
        "a provider's model with a key"            | [provider: "ANTHROPIC", baseUrl: null, apiKey: "sk-ant-1234567890"]
        "too few output tokens"                    | [maxOutputTokens: 100]
        "too many output tokens"                   | [maxOutputTokens: 2_000_000]
        "output tokens on an embedding model"      | [kind: "EMBEDDING", dimension: 1024, maxOutputTokens: 8192]

        base = [provider: "OPENAI_COMPATIBLE", kind: "CHAT", modelName: "m-" + AiApi.unique(), displayName: "M",
                inputPrice: 0, outputPrice: 0, baseUrl: "https://models.example.com/v1"]
    }

    def "plain HTTP reaches the hosts the installation allows; Ollama chat models take a context length"() {
        when:
        def model = serverModel("OLLAMA", [baseUrl: "http://ollama-intern:11434", contextLength: 65536, callsTools: false])

        then:
        model.baseUrl == "http://ollama-intern:11434"
        model.contextLength == 65536
        model.callsTools == false
        model.keyHint == null

        expect: "an Ollama embedding model takes none"
        api.status(api.adminToken, "POST", "/admin/models", [provider: "OLLAMA", kind: "EMBEDDING", modelName: "e-" + AiApi.unique(),
                displayName: "E", dimension: 1024, inputPrice: 0, outputPrice: 0, baseUrl: "http://ollama-intern:11434",
                contextLength: 8192]) == HttpStatus.BAD_REQUEST
    }

    def "a chat model keeps its own output limit - left out, the installation's applies; an update changes it"() {
        when:
        def own = serverModel("OPENAI_COMPATIBLE", [baseUrl: "https://models.example.com/v1", maxOutputTokens: 32768])
        def standard = serverModel("OPENAI_COMPATIBLE", [baseUrl: "https://models.example.com/v1"])
        def changed = api.admin("PUT", "/admin/models/${own.id}", [provider: "OPENAI_COMPATIBLE", kind: "CHAT",
                modelName: own.modelName, displayName: own.displayName, inputPrice: 0, outputPrice: 0,
                baseUrl: own.baseUrl, maxOutputTokens: 65536]) as Map

        then:
        own.maxOutputTokens == 32768
        standard.maxOutputTokens == null
        changed.maxOutputTokens == 65536
    }

    def "the same model may run on several servers, once per server"() {
        given:
        def first = serverModel("OLLAMA", [baseUrl: "https://a.example.com"])

        expect:
        api.status(api.adminToken, "POST", "/admin/models", first + [displayName: "B", baseUrl: "https://b.example.com"]) == HttpStatus.CREATED
        api.status(api.adminToken, "POST", "/admin/models", first + [displayName: "A2", baseUrl: "https://a.example.com/"]) == HttpStatus.CONFLICT
    }

    def "a model on an own server is usable without a provider key - its key is the admin's, billed at its price"() {
        given:
        def model = serverModel("OPENAI_COMPATIBLE", [baseUrl: "https://models.example.com/v1"])
        def tenant = api.tenant(false)
        api.allow(tenant.id, model)
        def token = api.userToken(tenant.id)

        when:
        def listed = (api.call(token, "GET", "/tenant/ai/models") as List<Map>).find { it.id == model.id }
        def answer = api.ask(token, [modelId: model.id, message: "Hallo?"])

        then:
        listed.keySource == "GLOBAL"
        answer.answer.content.startsWith("Antwort auf: Hallo?")
        fake.chats.last().apiKey == null
        (api.admin("GET", "/admin/usage?tenantId=${tenant.id}") as List<Map>)
                .any { it.provider == "OPENAI_COMPATIBLE" && it.keySource == "GLOBAL" && it.calls == 2 } // answer and title
    }

    def "an own server's key is not kept under API-Keys"() {
        given:
        def tenant = api.tenant()
        def token = api.tenantAdminToken(tenant.id)

        expect:
        api.status(api.adminToken, "PUT", "/admin/provider-keys/OLLAMA", [apiKey: "sk-some-key-1234"]) == HttpStatus.BAD_REQUEST
        api.status(token, "PUT", "/tenant/admin/provider-keys/OPENAI_COMPATIBLE", [apiKey: "sk-some-key-1234"]) == HttpStatus.BAD_REQUEST
    }

    static final Map BEDROCK = [baseUrl: "https://bedrock-runtime.eu-central-1.amazonaws.com",
                                apiKey : "AKIAEXAMPLE123456789:geheimes/Secret+1234"]

    private Map serverModel(String provider, Map changes) {
        api.admin("POST", "/admin/models", [provider: provider, kind: "CHAT", modelName: "server-" + AiApi.unique(),
                                            displayName: "Server " + AiApi.unique(), inputPrice: 0, outputPrice: 0] + changes) as Map
    }
}
