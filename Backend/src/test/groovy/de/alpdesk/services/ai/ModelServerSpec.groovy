package de.alpdesk.services.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.alpdesk.entities.ai.AiModel
import de.alpdesk.entities.ai.AiProvider
import de.alpdesk.entities.ai.ModelKind
import de.alpdesk.entities.assistant.Creativity
import de.alpdesk.entities.assistant.Reasoning
import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.services.common.ServiceException
import dev.langchain4j.data.image.Image
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.output.FinishReason
import io.micronaut.http.HttpStatus
import io.micronaut.serde.ObjectMapper
import spock.lang.Specification

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The real AiModels against own model servers (docs/architecture/ai-features.md "Own model servers"): a small server
 * in the spec answers like Ollama (/api/chat, /api/embed), an OpenAI-compatible server (/v1/chat/completions,
 * /v1/embeddings) or Amazon Bedrock (/model/<id>/converse, its stream in AWS's event stream format) - no provider is
 * called. It checks what goes out: the address, the key, the context length, no
 * "dimensions"; and that a redirect is not followed and failures name the model, never the server. Gemini's image
 * models too: the one provider call not going to an own server, asked at the spec's server through AiModels' test
 * address - so a langchain4j update that changes how images go out or come back fails here.
 */
class ModelServerSpec extends Specification {

    static final String BEDROCK_ADDRESS = "https://bedrock-runtime.eu-central-1.amazonaws.com"
    static final String BEDROCK_KEY = "AKIAEXAMPLE123456789:geheimes/Secret+1234"

    FakeServer server = new FakeServer()
    /** Bedrock's requests go to the spec's server - signed for the region of the model's address all the same. */
    BedrockClients bedrock = new BedrockClients(Duration.ofSeconds(5), server.url(""))
    /** Plain HTTP is allowed to 127.0.0.1 only - like a server in the same pod. */
    AiModels aiModels = new AiModels(Duration.ofSeconds(5), 100, new ModelServers("127.0.0.1"), bedrock)

    def cleanup() {
        server.stop()
        bedrock.close()
    }

    def "an OpenAI-compatible server is asked at its address with its key"() {
        given:
        server.json("/v1/chat/completions", 200, openAiAnswer("Hallo aus der Serverfarm"))

        when:
        ChatResponse response = aiModels.chat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")), "sk-farm-key-123",
                [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        response.aiMessage().text() == "Hallo aus der Serverfarm"
        response.tokenUsage().inputTokenCount() == 5
        def request = server.requests.first()
        request.path == "/v1/chat/completions"
        request.authorization == "Bearer sk-farm-key-123"
        request.body.model == "firmen-modell"
    }

    def "a server without a key gets no Authorization header"() {
        given:
        server.json("/v1/chat/completions", 200, openAiAnswer("Ohne Key"))
        server.json("/api/chat", 200, ollamaAnswer("Ohne Key", true))

        when:
        aiModels.chat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")), null, [UserMessage.from("Hallo?")], [], ChatOptions.NONE)
        aiModels.chat(model(AiProvider.OLLAMA, server.url("")), null, [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        server.requests*.authorization == [null, null]
    }

    def "Ollama is asked natively with the context length - the catalog's, or 32768"() {
        given:
        server.json("/api/chat", 200, ollamaAnswer("Hallo von Ollama", true))

        when:
        ChatResponse response = aiModels.chat(model(AiProvider.OLLAMA, server.url(""), null, contextLength), "proxy-key-123",
                [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        response.aiMessage().text() == "Hallo von Ollama"
        def request = server.requests.first()
        request.path == "/api/chat"
        request.authorization == "Bearer proxy-key-123"
        request.body.model == "firmen-modell"
        request.body.options.num_ctx == sent
        request.body.options.num_predict == 100

        where:
        contextLength | sent
        null          | 32768
        8192          | 8192
    }

    def "the tenant's creativity and reasoning reach Ollama: temperature, think, the output limit with the budget"() {
        given:
        server.json("/api/chat", 200, ollamaAnswer("ok", true))

        when:
        aiModels.chat(model(AiProvider.OLLAMA, server.url("")), null, [UserMessage.from("Hallo?")], [],
                new ChatOptions(Creativity.HIGH, Reasoning.OFF))
        aiModels.chat(model(AiProvider.OLLAMA, server.url("")), null, [UserMessage.from("Hallo?")], [],
                new ChatOptions(Creativity.LOW, Reasoning.HIGH))

        then:
        def (off, high) = server.requests*.body
        off.options.temperature == 1.0
        off.think == false
        off.options.num_predict == 100
        high.options.temperature == 0.2
        high.think == true
        high.options.num_predict == 100 + 16384
    }

    def "an OpenAI-compatible server gets the temperature and low/medium/high - nothing for reasoning off"() {
        given:
        server.json("/v1/chat/completions", 200, openAiAnswer("ok"))

        when:
        aiModels.chat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")), null, [UserMessage.from("Hallo?")], [],
                new ChatOptions(Creativity.LOW, Reasoning.MEDIUM))
        aiModels.chat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")), null, [UserMessage.from("Hallo?")], [],
                new ChatOptions(Creativity.MEDIUM, Reasoning.OFF))

        then:
        def (medium, off) = server.requests*.body
        medium.temperature == 0.2
        medium.reasoning_effort == "medium"
        medium.max_tokens == 100 + 8192
        off.temperature == 0.7
        !off.containsKey("reasoning_effort")
        off.max_tokens == 100
    }

    def "a model that takes neither gets neither"() {
        given:
        server.json("/api/chat", 200, ollamaAnswer("ok", true))
        server.json("/v1/chat/completions", 200, openAiAnswer("ok"))
        def options = new ChatOptions(Creativity.HIGH, Reasoning.HIGH)

        when:
        aiModels.chat(model(AiProvider.OLLAMA, server.url(""), null, null, false, false), null, [UserMessage.from("Hallo?")], [], options)
        aiModels.chat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1"), null, null, false, false), null,
                [UserMessage.from("Hallo?")], [], options)

        then:
        def (ollama, openAi) = server.requests*.body
        !ollama.options.containsKey("temperature")
        !ollama.containsKey("think")
        ollama.options.num_predict == 100
        !openAi.containsKey("temperature")
        !openAi.containsKey("reasoning_effort")
    }

    def "both stream their answers"() {
        given:
        server.stream("/v1/chat/completions", "text/event-stream", [
                "data: " + json(openAiChunk("Hallo ", null)) + "\n\n",
                "data: " + json(openAiChunk("Welt", "stop")) + "\n\n",
                "data: [DONE]\n\n"])
        server.stream("/api/chat", "application/x-ndjson", [
                json(ollamaAnswer("Hallo ", false)) + "\n",
                json(ollamaAnswer("Welt", true)) + "\n"])

        expect:
        streamed(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1"))) == ["Hallo ", "Welt"]
        streamed(model(AiProvider.OLLAMA, server.url(""))) == ["Hallo ", "Welt"]
    }

    def "an OpenAI-compatible server reporting cached tokens (vLLM's prefix caching): taken off the input, priced apart"() {
        given: "10 000 prompt tokens, 8 000 of them from the server's cache"
        def usage = [prompt_tokens: 10_000, completion_tokens: 2, total_tokens: 10_002, prompt_tokens_details: [cached_tokens: 8_000]]
        server.json("/v1/chat/completions", 200, openAiAnswer("Hallo Welt") + [usage: usage])
        def model = model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1"))

        when:
        def whole = aiModels.chat(model, null, [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then: "2 000 input tokens, 8 000 read from the cache - the prompt's length is still 10 000"
        CallTokens.of(whole.tokenUsage()) == new CallTokens(2_000, 2, 8_000, 0)
        CallTokens.of(whole.tokenUsage()).promptLength() == 10_000

        and: "without a cache price the cached tokens cost like input (the catalog's 1 per million here)"
        UsageService.amount(model, CallTokens.of(whole.tokenUsage())) == 0.010002
    }

    def "a streamed answer brings the cached tokens in its last chunk - the agent loop streams every round"() {
        given:
        def usage = [prompt_tokens: 10_000, completion_tokens: 2, total_tokens: 10_002, prompt_tokens_details: [cached_tokens: 8_000]]
        server.stream("/v1/chat/completions", "text/event-stream", [
                "data: " + json(openAiChunk("Hallo ", null)) + "\n\n",
                "data: " + json(openAiChunk("Welt", "stop")) + "\n\n",
                "data: " + json([id: "chatcmpl-1", object: "chat.completion.chunk", created: 1, model: "firmen-modell",
                                 choices: [], usage: usage]) + "\n\n",
                "data: [DONE]\n\n"])

        expect:
        CallTokens.of(streamedResponse(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1"))).tokenUsage()) ==
                new CallTokens(2_000, 2, 8_000, 0)
    }

    def "a stream that falls silent after its first part ends with an error after the timeout: #provider"() {
        given: "the request's timeout only bounds the wait for the headers - the stream would never end"
        def release = new java.util.concurrent.CountDownLatch(1)
        server.stalling(path, contentType, first, release)
        def models = new AiModels(Duration.ofSeconds(1), 100, new ModelServers("127.0.0.1"), bedrock)
        def parts = new CopyOnWriteArrayList<String>()
        def ended = new CompletableFuture<Throwable>()

        when:
        def address = provider == AiProvider.BEDROCK ? BEDROCK_ADDRESS : server.url(base)
        models.streamChat(model(provider, address), key, [UserMessage.from("Hallo?")], [], ChatOptions.NONE,
                new StreamingChatResponseHandler() {
                    void onPartialResponse(String part) { parts << part }

                    void onCompleteResponse(ChatResponse response) { ended.complete(null) }

                    void onError(Throwable error) { ended.complete(error) }
                })
        def error = ended.get(10, TimeUnit.SECONDS)
        release.countDown()

        then:
        parts == ["Hallo "]
        error instanceof ServiceException
        (error as ServiceException).code() == "provider.stalled"
        (error as ServiceException).status() == HttpStatus.BAD_GATEWAY
        error.message == "Firmen-KI stopped answering - please try again"

        where:
        provider                     | base  | path                   | contentType            | first
        AiProvider.OPENAI_COMPATIBLE | "/v1" | "/v1/chat/completions" | "text/event-stream"    | "data: " + json(openAiChunk("Hallo ", null)) + "\n\n"
        AiProvider.OLLAMA            | ""    | "/api/chat"            | "application/x-ndjson" | json(ollamaAnswer("Hallo ", false)) + "\n"
        AiProvider.BEDROCK           | ""    | "/model/firmen-modell/converse-stream" | "application/vnd.amazon.eventstream" | concat(bedrockStream(["Hallo "], [:]).take(2))

        key = provider == AiProvider.BEDROCK ? BEDROCK_KEY : null
    }

    def "a thinking model's reasoning streams to the chat, apart from its answer - vLLM's 'reasoning', Ollama's 'thinking'"() {
        given:
        server.stream("/v1/chat/completions", "text/event-stream", [
                "data: " + json(openAiReasoningChunk("Erst ")) + "\n\n",
                "data: " + json(openAiReasoningChunk("überlegen.")) + "\n\n",
                "data: " + json(openAiChunk("Hallo ", null)) + "\n\n",
                "data: " + json(openAiChunk("Welt", "stop")) + "\n\n",
                "data: [DONE]\n\n"])
        server.stream("/api/chat", "application/x-ndjson", [
                json([model: "firmen-modell", created_at: "2026-10-06T10:00:00Z",
                      message: [role: "assistant", content: "", thinking: "Erst überlegen."], done: false]) + "\n",
                json(ollamaAnswer("Hallo ", false)) + "\n",
                json(ollamaAnswer("Welt", true)) + "\n"])

        when:
        def vllm = streamedWithThinking(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")))
        def ollama = streamedWithThinking(model(AiProvider.OLLAMA, server.url("")))

        then:
        vllm.thinking.join() == "Erst überlegen."
        vllm.parts == ["Hallo ", "Welt"]
        ollama.thinking.join() == "Erst überlegen."
        ollama.parts == ["Hallo ", "Welt"]
    }

    def "reasoning looping on an OpenAI-compatible server is stopped at the fifth copy - the round ends without text"() {
        given: "a model planning its answer in two paragraphs, again and again, as vLLM streams it"
        def cycle = ["Okay.", "Let's write.", "The answer will be in German, with the class name `KM4`.", "",
                     "Okay.", "Let's go.", "Answer: the KM-4 for up to four boilers, the leading boiler first.",
                     "One detail: the manual names busAddress as required.", "I'll focus on it.", ""]
        def chunks = ["Ich suche nach dem Kaskadenmodul."] + cycle * 8
        server.stream("/v1/chat/completions", "text/event-stream",
                chunks.collect { "data: " + json(openAiReasoningChunk(it + "\n")) + "\n\n" }
                        + ["data: " + json(openAiChunk("Zu spät.", "stop")) + "\n\n", "data: [DONE]\n\n"])
        def thinking = new CopyOnWriteArrayList<String>()
        def done = new CompletableFuture<ChatResponse>()

        when:
        aiModels.streamChat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")), null, [UserMessage.from("Welches Modul?")], [],
                ChatOptions.NONE, new StreamingChatResponseHandler() {
                    void onPartialResponse(String part) { }

                    void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partial) { thinking << partial.text() }

                    void onCompleteResponse(ChatResponse response) { done.complete(response) }

                    void onError(Throwable error) { done.completeExceptionally(error) }
                })
        def response = done.get(10, TimeUnit.SECONDS)

        then: "cancelled with the fifth copy: no text, and the agent loop asks again"
        response.aiMessage().text() == ""
        response.finishReason() == FinishReason.OTHER
        thinking.join("") == (["Ich suche nach dem Kaskadenmodul."] + cycle * 5).collect { it + "\n" }.join("")
    }

    def "the output limit sent is the catalog entry's, else the installation's - reasoning included"() {
        given:
        server.json("/v1/chat/completions", 200, openAiAnswer("Hallo"))

        when:
        aiModels.chat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1"), null, null, true, false, 32768), null,
                [UserMessage.from("Hallo?")], [], ChatOptions.NONE)
        aiModels.chat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1"), null, null, true, false), null,
                [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        // This spec's AiModels has an installation default of 100.
        server.requests*.body*.max_tokens == [32768, 100]
    }

    def "embeddings: no dimensions are asked for, the vectors' length is checked"() {
        given:
        server.json("/v1/embeddings", 200, [object: "list", model: "firmen-embedding",
                                            data  : [[object: "embedding", index: 0, embedding: [0.1, 0.2, 0.3]]],
                                            usage : [prompt_tokens: 3, total_tokens: 3]])
        server.json("/api/embed", 200, [model: "firmen-embedding", embeddings: [[0.1, 0.2, 0.3]], prompt_eval_count: 3])

        when:
        def openAi = aiModels.embed(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1"), 3), "sk-farm-key-123",
                [TextSegment.from("Text")])
        def ollama = aiModels.embed(model(AiProvider.OLLAMA, server.url(""), 3), null, [TextSegment.from("Text")])

        then:
        openAi.content().first().dimension() == 3
        ollama.content().first().dimension() == 3
        server.requests*.path == ["/v1/embeddings", "/api/embed"]
        !server.requests[0].body.containsKey("dimensions")

        when: "the catalog says another length"
        aiModels.embed(model(AiProvider.OLLAMA, server.url(""), 1024), null, [TextSegment.from("Text")])

        then:
        def e = thrown(ServiceException)
        e.status() == HttpStatus.BAD_GATEWAY
        e.message == "Firmen-KI returns vectors of length 3, the model catalog says 1024"
    }

    def "a redirect is not followed - the key never reaches another server"() {
        given:
        def elsewhere = new FakeServer()
        elsewhere.json("/v1/chat/completions", 200, openAiAnswer("Umgeleitet"))
        server.redirect("/v1/chat/completions", elsewhere.url("/v1/chat/completions"))

        when:
        aiModels.chat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")), "sk-farm-key-123", [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        thrown(ServiceException)
        elsewhere.requests.isEmpty()

        cleanup:
        elsewhere.stop()
    }

    def "reading in the background is asked again after HTTP #status - the chat is not"() {
        given:
        def failure = [status, [error: [message: "Bitte später", type: "server_error"]]]
        server.answers("/v1/chat/completions", [failure, [200, openAiAnswer("Gelesene Seite")], failure,
                                                [200, openAiAnswer("Hallo")]])
        def reader = model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1"))

        when:
        ChatResponse response = aiModels.chatInBackground(reader, "k", [UserMessage.from("Seite 1:")])

        then:
        response.aiMessage().text() == "Gelesene Seite"
        server.requests.size() == 2

        when:
        aiModels.chat(reader, "k", [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        thrown(ServiceException)
        server.requests.size() == 3

        where:
        status << [429, 500]
    }

    def "a failure asked again after leaves the server's answer out of the log - it may repeat the document"() {
        given:
        def failure = [500, [error: [message: "Wiederholt: Firmengeheimnis Musterfirma", type: "server_error"]]]
        server.answers("/v1/chat/completions", [failure, [200, openAiAnswer("Gelesene Seite")]])
        server.answers("/api/embed", [failure, [200, [model: "firmen-embedding", embeddings: [[0.1, 0.2, 0.3]],
                                                     prompt_eval_count: 3]]])

        when:
        aiModels.chatInBackground(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")), "k", [UserMessage.from("Seite 1:")])
        aiModels.embed(model(AiProvider.OLLAMA, server.url(""), 3), null, [TextSegment.from("Firmengeheimnis Musterfirma")])

        then:
        server.requests.size() == 4
        RecentLogAppender.installed().entries().every {
            !it.message().contains("Firmengeheimnis") && !it.stackTrace()?.contains("Firmengeheimnis")
        }
    }

    def "reading in the background is not asked again when the request itself is refused: HTTP #status"() {
        given:
        server.json("/v1/chat/completions", status, [error: [message: "Abgelehnt", type: "invalid_request_error"]])

        when:
        aiModels.chatInBackground(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")), "k", [UserMessage.from("Seite 1:")])

        then:
        thrown(ServiceException)
        server.requests.size() == 1

        where:
        status << [400, 401, 403, 404]
    }

    def "failures name the model, never the server's address"() {
        given:
        server.json("/v1/chat/completions", 400, [error: [message: "This model's maximum context length is 8192 tokens",
                                                          type   : "invalid_request_error", code: "context_length_exceeded"]])
        server.json("/v1/embeddings", 401, [error: [message: "Incorrect API key provided", type: "invalid_request_error"]])
        def unreachable = model(AiProvider.OPENAI_COMPATIBLE, "http://127.0.0.1:1/v1")

        when:
        aiModels.chat(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1")), "k", [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        def tooLong = thrown(ServiceException)
        tooLong.message == "The conversation is too long for Firmen-KI - please start a new one"

        when:
        aiModels.embed(model(AiProvider.OPENAI_COMPATIBLE, server.url("/v1"), 3), "k", [TextSegment.from("Text")])

        then:
        def refused = thrown(ServiceException)
        refused.message == "Firmen-KI refused the API key - ask the admin"

        when:
        aiModels.chat(unreachable, null, [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        def down = thrown(ServiceException)
        down.status() == HttpStatus.BAD_GATEWAY
        down.message == "Firmen-KI is not reachable - please try again later"
    }

    def "Bedrock is asked through its Converse API at the address's region, signed with the access key - the secret never goes out"() {
        given:
        server.json("/model/firmen-modell/converse", 200, bedrockAnswer("Hallo aus Frankfurt"))

        when:
        ChatResponse response = aiModels.chat(model(AiProvider.BEDROCK, BEDROCK_ADDRESS), BEDROCK_KEY,
                [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        response.aiMessage().text() == "Hallo aus Frankfurt"
        CallTokens.of(response.tokenUsage()) == new CallTokens(5, 2, 0, 0)
        def request = server.requests.first()
        request.path == "/model/firmen-modell/converse"
        request.authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE123456789/")
        request.authorization.contains("/eu-central-1/bedrock/aws4_request")
        !request.toString().contains("geheim")
        request.body.messages == [[role: "user", content: [[text: "Hallo?"]]]]
        request.body.inferenceConfig.maxTokens == 100
    }

    def "Bedrock streams like Claude: thinking without a temperature, the rounds cached up to the question, cache tokens"() {
        given:
        server.bytes("/model/firmen-modell/converse-stream", "application/vnd.amazon.eventstream", bedrockStream(
                ["Hallo ", "Welt"], [inputTokens: 2_000, outputTokens: 2, totalTokens: 10_502, cacheReadInputTokens: 8_000,
                                     cacheWriteInputTokens: 500]))
        def parts = new CopyOnWriteArrayList<String>()
        def done = new CompletableFuture<ChatResponse>()
        def tools = [dev.langchain4j.agent.tool.ToolSpecification.builder().name("similarity_search").build()]

        when: "a round of the agent loop whose tools may no longer be called"
        aiModels.streamChat(model(AiProvider.BEDROCK, BEDROCK_ADDRESS), BEDROCK_KEY, [UserMessage.from("Hallo?")], tools,
                false, new ChatOptions(Creativity.HIGH, Reasoning.MEDIUM, "answer-1"), new StreamingChatResponseHandler() {
            void onPartialResponse(String part) { parts << part }

            void onCompleteResponse(ChatResponse response) { done.complete(response) }

            void onError(Throwable error) { done.completeExceptionally(error) }
        })
        def response = done.get(10, TimeUnit.SECONDS)

        then:
        parts == ["Hallo ", "Welt"]
        response.aiMessage().text() == "Hallo Welt"
        CallTokens.of(response.tokenUsage()) == new CallTokens(2_000, 2, 8_000, 500)
        server.requests.first().authorization.contains("/eu-central-1/bedrock/aws4_request")

        and: "Claude thinks without a temperature, the budget on top of the output limit"
        def body = server.requests.first().body
        body.additionalModelRequestFields.reasoning_config == [type: "enabled", budget_tokens: 8192]
        !body.inferenceConfig.containsKey("temperature")
        body.inferenceConfig.maxTokens == 100 + 8192

        and: "cached up to the question; the tools listed - Claude refuses the history's tool calls without them"
        body.messages.last().content.any { it.cachePoint }
        body.toolConfig.tools*.toolSpec*.name == ["similarity_search"]
    }

    def "Bedrock's refusals become the usual failures, its error code in the log - never its message: #errorType"() {
        given:
        server.awsError("/model/firmen-modell/converse", status, errorType, "Firmengeheimnis im Fehlertext")

        when:
        aiModels.chat(model(AiProvider.BEDROCK, BEDROCK_ADDRESS), BEDROCK_KEY, [UserMessage.from("Hallo?")], [],
                ChatOptions.NONE)

        then:
        def failure = thrown(ServiceException)
        failure.code() == code
        failure.status() == HttpStatus.BAD_GATEWAY
        RecentLogAppender.installed().entries().any { it.message().contains("(HTTP ${status}) ${errorType}") }
        RecentLogAppender.installed().entries().every { !it.message().contains("Firmengeheimnis") }

        where:
        status | errorType                     || code
        403    | "UnrecognizedClientException" || "provider.keyRefusedOwnServer"
        403    | "AccessDeniedException"       || "provider.keyRefusedOwnServer"
        404    | "ResourceNotFoundException"   || "provider.modelUnknown"
        429    | "ThrottlingException"         || "provider.rateLimited"
    }

    def "a conversation too long for Claude on Bedrock is told so - Bedrock's own wording"() {
        given:
        server.awsError("/model/firmen-modell/converse", 400, "ValidationException", "Input is too long for requested model.")

        when:
        aiModels.chat(model(AiProvider.BEDROCK, BEDROCK_ADDRESS), BEDROCK_KEY, [UserMessage.from("Hallo?")], [],
                ChatOptions.NONE)

        then:
        def failure = thrown(ServiceException)
        failure.code() == "provider.contextTooLong"
        failure.message == "The conversation is too long for Firmen-KI - please start a new one"
    }

    def "a Bedrock address names its region: #address"() {
        expect:
        BedrockClients.region(address) == Optional.ofNullable(region)

        where:
        address                                                                    || region
        "https://bedrock-runtime.eu-central-1.amazonaws.com"                       || "eu-central-1"
        "https://bedrock-runtime-fips.us-east-1.amazonaws.com"                     || "us-east-1"
        "https://vpce-0a1b2c.bedrock-runtime.eu-west-3.vpce.amazonaws.com"         || "eu-west-3"
        "https://bedrock.eu-central-1.amazonaws.com"                               || null
        "https://bedrock-mantle.eu-central-1.api.aws"                              || null
        "https://models.example.com/bedrock-runtime.eu-central-1.amazonaws.com"    || null
        "https://bedrock runtime.eu-central-1.amazonaws.com"                       || null
    }

    def "a Bedrock key is the access key's id and secret: #key"() {
        expect:
        BedrockClients.isAccessKey(key) == valid

        where:
        key                                         || valid
        "AKIAEXAMPLE123456789:geheimes/Secret+1234" || true
        "AKIAEXAMPLE123456789"                      || false
        "akiaexample123456789:secret"               || false
        "AKIAEXAMPLE123456789:"                     || false
        "AKIAEXAMPLE123456789:mit leerzeichen"      || false
    }

    def "a TEI reranker gets the question and the texts - its scores come back in the texts' order"() {
        given: "TEI answers sorted by score, each with its text's index"
        server.stream("/rerank", "application/json", [json([[index: 2, score: 0.9], [index: 0, score: 0.4], [index: 1, score: 0.1]])])

        when:
        def scores = aiModels.rerank(reranker(server.url("")), "tei-key-123", "Wie oft?", ["eins", "zwei", "drei"])

        then:
        scores == [0.4d, 0.1d, 0.9d]
        def request = server.requests.first()
        request.path == "/rerank"
        request.authorization == "Bearer tei-key-123"
        request.body == [query: "Wie oft?", texts: ["eins", "zwei", "drei"], truncate: true]
    }

    def "a reranker without a key gets no Authorization header"() {
        given:
        server.stream("/rerank", "application/json", [json([[index: 0, score: 0.5]])])

        when:
        aiModels.rerank(reranker(server.url("")), null, "Wie oft?", ["eins"])

        then:
        server.requests*.authorization == [null]
    }

    def "a reranker's failure names the model, never the server: #failure"() {
        given:
        if (status == 200) {
            server.stream("/rerank", "application/json", [json(answer)])
        } else {
            server.answers("/rerank", [[status, [error: "kaputt"]]])
        }

        when:
        aiModels.rerank(reranker(server.url("")), "tei-key-123", "Wie oft?", ["eins", "zwei"])

        then:
        def e = thrown(ServiceException)
        e.status() == HttpStatus.BAD_GATEWAY
        e.code() == code
        !e.message.contains("127.0.0.1")

        where:
        failure             | status | answer                                         || code
        "key refused"       | 401    | null                                           || "provider.keyRefusedOwnServer"
        "rate limited"      | 429    | null                                           || "provider.rateLimited"
        "server error"      | 500    | null                                           || "provider.failed"
        "a score missing"   | 200    | [[index: 0, score: 0.5]]                       || "provider.failed"
        "an unknown index"  | 200    | [[index: 0, score: 0.5], [index: 7, score: 1]] || "provider.failed"
    }

    def "a reranker out of reach is named so"() {
        given:
        def address = server.url("")
        server.stop()

        when:
        aiModels.rerank(reranker(address), null, "Wie oft?", ["eins"])

        then:
        def e = thrown(ServiceException)
        e.code() == "provider.unreachable"
        e.message == "Firmen-Reranker is not reachable - please try again later"
    }

    def "a host no longer allowed over plain HTTP is not called"() {
        given:
        def strict = new AiModels(Duration.ofSeconds(5), 100, new ModelServers(""), bedrock)
        server.json("/api/chat", 200, ollamaAnswer("Hallo", true))

        when:
        strict.chat(model(AiProvider.OLLAMA, server.url("")), "proxy-key-123", [UserMessage.from("Hallo?")], [], ChatOptions.NONE)

        then:
        def e = thrown(ServiceException)
        e.message == "The server of Firmen-KI may not be reached over plain HTTP - ask the admin"
        server.requests.isEmpty()
    }

    def "Gemini's image model gets the instruction, then the images in order - its image, text and usage come back"() {
        given:
        byte[] house = [1, 2, 3] as byte[]
        byte[] garden = [4, 5] as byte[]
        byte[] result = [9, 8, 7, 6] as byte[]
        server.json("/v1beta/models/gemini-bild:generateContent", 200,
                geminiAnswer([[text: "Hier ist das Haus am Abend."], [inlineData: [mimeType: "image/png", data: base64(result)]]]))

        when:
        def created = gemini().createImage(geminiImageModel(), "gemini-key-123", "Das Haus am Abend",
                [image(house, "image/png"), image(garden, "image/jpeg")])

        then: "one call to Google's path with the key in its header"
        server.requests.size() == 1
        def request = server.requests.first()
        request.path == "/v1beta/models/gemini-bild:generateContent"
        request.apiKey == "gemini-key-123"

        and: "the instruction first, then the images as they were named"
        def parts = request.body.contents[0].parts as List<Map>
        parts[0].text == "Das Haus am Abend"
        parts[1].inlineData == [mimeType: "image/png", data: base64(house)]
        parts[2].inlineData == [mimeType: "image/jpeg", data: base64(garden)]
        parts.size() == 3

        and:
        created.hasImage()
        created.content() == result
        created.mimeType() == "image/png"
        created.text() == "Hier ist das Haus am Abend."
        created.usage().inputTokenCount() == 268
        created.usage().outputTokenCount() == 1290
    }

    def "an image Gemini refuses comes back as its text with the usage - the call is billed"() {
        given:
        server.json("/v1beta/models/gemini-bild:generateContent", 200,
                geminiAnswer([[text: "Dabei kann ich nicht helfen."]]))

        when:
        def created = gemini().createImage(geminiImageModel(), "gemini-key-123", "Etwas Verbotenes", [])

        then:
        !created.hasImage()
        created.text() == "Dabei kann ich nicht helfen."
        created.usage().outputTokenCount() == 1290
    }

    def "Gemini's failures name it, never its answer, and are not retried: HTTP #status"() {
        given:
        server.json("/v1beta/models/gemini-bild:generateContent", status,
                [error: [code: status, message: "Firmengeheimnis im Prompt", status: "SOME_STATUS"]])

        when:
        gemini().createImage(geminiImageModel(), "gemini-key-123", "Firmengeheimnis im Prompt", [])

        then:
        def e = thrown(ServiceException)
        e.status() == HttpStatus.BAD_GATEWAY
        e.message == message
        server.requests.size() == 1

        where:
        status | message
        403    | "GEMINI refused the API key - check it under API-Keys"
        429    | "GEMINI refused the request: rate limit or quota reached"
        500    | "GEMINI failed - please try again"
    }

    /** The streamed reasoning and answer parts of one call. */
    private Map<String, List<String>> streamedWithThinking(AiModel model) {
        List<String> parts = new CopyOnWriteArrayList<>()
        List<String> thinking = new CopyOnWriteArrayList<>()
        def done = new CompletableFuture<ChatResponse>()
        aiModels.streamChat(model, null, [UserMessage.from("Hallo?")], [], ChatOptions.NONE, new StreamingChatResponseHandler() {
            void onPartialResponse(String part) { parts << part }

            void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partial) { thinking << partial.text() }

            void onCompleteResponse(ChatResponse response) { done.complete(response) }

            void onError(Throwable error) { done.completeExceptionally(error) }
        })
        assert done.get(5, TimeUnit.SECONDS).aiMessage().text() == "Hallo Welt"
        [parts: parts, thinking: thinking]
    }

    private List<String> streamed(AiModel model) {
        List<String> parts = new CopyOnWriteArrayList<>()
        streamedResponse(model, parts)
        parts
    }

    /** The whole response of a streamed answer "Hallo Welt" - its parts collected into parts. */
    private ChatResponse streamedResponse(AiModel model, List<String> parts = new CopyOnWriteArrayList<>()) {
        def done = new CompletableFuture<ChatResponse>()
        aiModels.streamChat(model, null, [UserMessage.from("Hallo?")], [], ChatOptions.NONE, new StreamingChatResponseHandler() {
            void onPartialResponse(String part) { parts << part }

            void onCompleteResponse(ChatResponse response) { done.complete(response) }

            void onError(Throwable error) { done.completeExceptionally(error) }
        })
        ChatResponse response = done.get(5, TimeUnit.SECONDS)
        assert response.aiMessage().text() == "Hallo Welt"
        response
    }

    private static AiModel model(AiProvider provider, String baseUrl, Integer dimension = null, Integer contextLength = null,
                                 boolean takesTemperature = true, boolean steersReasoning = true,
                                 Integer maxOutputTokens = null) {
        new AiModel(1L, provider, dimension ? ModelKind.EMBEDDING : ModelKind.CHAT,
                dimension ? "firmen-embedding" : "firmen-modell", "Firmen-KI", dimension, BigDecimal.ONE, BigDecimal.ONE,
                true, false, false, false, false, takesTemperature, steersReasoning, true, baseUrl, null, null,
                contextLength, maxOutputTokens, null, null, null, null, null, null, null, Instant.now(), Instant.now())
    }

    private static AiModel reranker(String baseUrl) {
        new AiModel(1L, AiProvider.TEI, ModelKind.RERANK, "bge-reranker-v2-m3", "Firmen-Reranker", null, BigDecimal.ZERO,
                BigDecimal.ZERO, true, false, false, false, false, false, false, false, baseUrl, null, null, null, null,
                null, null, null, null, null, null, null, Instant.now(), Instant.now())
    }

    /** The real AiModels asking Gemini's image models at the spec's server instead of Google's. */
    private AiModels gemini() {
        new AiModels(Duration.ofSeconds(5), 100, new ModelServers("127.0.0.1"), bedrock, server.url("/v1beta"))
    }

    private static AiModel geminiImageModel() {
        new AiModel(1L, AiProvider.GEMINI, ModelKind.IMAGE, "gemini-bild", "Gemini Bild", null, BigDecimal.ONE,
                BigDecimal.ONE, true, false, false, false, false, false, false, false, null, null, null,
                null, null, null, null, null, null, null, null, null, Instant.now(), Instant.now())
    }

    /** Gemini's generateContent answer: one candidate with these parts, and the usage. */
    private static Map geminiAnswer(List<Map> parts) {
        [candidates   : [[content: [role: "model", parts: parts], finishReason: "STOP", index: 0]],
         usageMetadata: [promptTokenCount: 268, candidatesTokenCount: 1290, totalTokenCount: 1558],
         modelVersion : "gemini-bild"]
    }

    private static Image image(byte[] content, String mimeType) {
        Image.builder().base64Data(base64(content)).mimeType(mimeType).build()
    }

    private static String base64(byte[] content) {
        Base64.encoder.encodeToString(content)
    }

    private static Map openAiAnswer(String text) {
        [id     : "chatcmpl-1", object: "chat.completion", created: 1, model: "firmen-modell",
         choices: [[index: 0, message: [role: "assistant", content: text], finish_reason: "stop"]],
         usage  : [prompt_tokens: 5, completion_tokens: 2, total_tokens: 7]]
    }

    private static Map openAiChunk(String text, String finishReason) {
        [id     : "chatcmpl-1", object: "chat.completion.chunk", created: 1, model: "firmen-modell",
         choices: [[index: 0, delta: [role: "assistant", content: text], finish_reason: finishReason]]]
    }

    /** vLLM's reasoning delta: the field "reasoning" (formerly "reasoning_content"), no content. */
    private static Map openAiReasoningChunk(String reasoning) {
        [id     : "chatcmpl-1", object: "chat.completion.chunk", created: 1, model: "firmen-modell",
         choices: [[index: 0, delta: [role: "assistant", reasoning: reasoning], finish_reason: null]]]
    }

    private static Map bedrockAnswer(String text) {
        [output    : [message: [role: "assistant", content: [[text: text]]]], stopReason: "end_turn",
         usage     : [inputTokens: 5, outputTokens: 2, totalTokens: 7], metrics: [latencyMs: 1]]
    }

    /** ConverseStream's events: the message's start, a text block in these parts, its stop, the usage. */
    private static List<byte[]> bedrockStream(List<String> texts, Map usage) {
        List<byte[]> events = [awsEvent("messageStart", [role: "assistant"])]
        texts.each { events << awsEvent("contentBlockDelta", [contentBlockIndex: 0, delta: [text: it]]) }
        events + [awsEvent("contentBlockStop", [contentBlockIndex: 0]),
                  awsEvent("messageStop", [stopReason: "end_turn"]),
                  awsEvent("metadata", [usage: usage, metrics: [latencyMs: 1]])]
    }

    /**
     * One message of AWS's event stream (application/vnd.amazon.eventstream): its length and the headers' length, their
     * CRC32, the headers (name, type 7 = string, value), the JSON payload, the whole message's CRC32.
     */
    private static byte[] awsEvent(String type, Map payload) {
        def headers = new ByteArrayOutputStream()
        [":event-type": type, ":content-type": "application/json", ":message-type": "event"].each { name, value ->
            byte[] nameBytes = name.getBytes("UTF-8")
            byte[] valueBytes = value.getBytes("UTF-8")
            headers.write(nameBytes.length)
            headers.write(nameBytes)
            headers.write(7)
            headers.write((valueBytes.length >> 8) & 0xff)
            headers.write(valueBytes.length & 0xff)
            headers.write(valueBytes)
        }
        byte[] headerBytes = headers.toByteArray()
        byte[] payloadBytes = json(payload).getBytes("UTF-8")
        def message = new ByteArrayOutputStream()
        def out = new DataOutputStream(message)
        out.writeInt(16 + headerBytes.length + payloadBytes.length)
        out.writeInt(headerBytes.length)
        out.writeInt(crc(message.toByteArray()))
        out.write(headerBytes)
        out.write(payloadBytes)
        out.writeInt(crc(message.toByteArray()))
        message.toByteArray()
    }

    private static int crc(byte[] bytes) {
        def crc = new java.util.zip.CRC32()
        crc.update(bytes)
        (int) crc.value
    }

    private static byte[] concat(List<byte[]> parts) {
        def all = new ByteArrayOutputStream()
        parts.each { all.write(it) }
        all.toByteArray()
    }

    private static Map ollamaAnswer(String text, boolean done) {
        Map answer = [model: "firmen-modell", created_at: "2026-10-06T10:00:00Z", message: [role: "assistant", content: text],
                      done : done]
        done ? answer + [done_reason: "stop", prompt_eval_count: 5, eval_count: 2] : answer
    }

    private static String json(Object value) {
        ObjectMapper.getDefault().writeValueAsString(value)
    }

    /** Answers like a model server and records what it was sent: [path, authorization, apiKey (Gemini's header), body]. */
    static class FakeServer {

        final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        final List<Map> requests = new CopyOnWriteArrayList<>()

        FakeServer() {
            http.start()
        }

        String url(String path) {
            "http://127.0.0.1:${http.address.port}${path}"
        }

        void json(String path, int status, Map body) {
            handle(path) { HttpExchange exchange -> respond(exchange, status, "application/json", [json(body)]) }
        }

        /** Answers the calls in turn - [status, body] each -, the last one again for every further call. */
        void answers(String path, List<List> statusAndBody) {
            def next = new java.util.concurrent.atomic.AtomicInteger()
            handle(path) { HttpExchange exchange ->
                def answer = statusAndBody[Math.min(next.getAndIncrement(), statusAndBody.size() - 1)]
                respond(exchange, answer[0] as int, "application/json", [json(answer[1] as Map)])
            }
        }

        void stream(String path, String contentType, List<String> parts) {
            handle(path) { HttpExchange exchange -> respond(exchange, 200, contentType, parts) }
        }

        /** Answers with these parts as they are - a binary stream such as AWS's event stream. */
        void bytes(String path, String contentType, List<byte[]> parts) {
            handle(path) { HttpExchange exchange -> respond(exchange, 200, contentType, parts) }
        }

        /** Refuses like an AWS service: the error's type in x-amzn-ErrorType, its message in the body. */
        void awsError(String path, int status, String errorType, String message) {
            handle(path) { HttpExchange exchange ->
                exchange.responseHeaders.add("x-amzn-ErrorType", errorType)
                respond(exchange, status, "application/json", [json([message: message])])
            }
        }

        /** Sends the first part, then stays silent with the connection open until released. */
        void stalling(String path, String contentType, Object first, java.util.concurrent.CountDownLatch release) {
            handle(path) { HttpExchange exchange ->
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(first instanceof byte[] ? first as byte[] : first.toString().getBytes("UTF-8"))
                exchange.responseBody.flush()
                release.await(30, TimeUnit.SECONDS)
                exchange.close()
            }
        }

        void redirect(String path, String location) {
            handle(path) { HttpExchange exchange ->
                exchange.responseHeaders.add("Location", location)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
        }

        void stop() {
            http.stop(0)
        }

        private void handle(String path, Closure answer) {
            http.createContext(path) { HttpExchange exchange ->
                String body = exchange.requestBody.text
                requests << [path         : exchange.requestURI.path,
                             authorization: exchange.requestHeaders.getFirst("Authorization"),
                             apiKey       : exchange.requestHeaders.getFirst("x-goog-api-key"),
                             body         : body ? ObjectMapper.getDefault().readValue(body, Map) : [:]]
                answer(exchange)
            }
        }

        private static void respond(HttpExchange exchange, int status, String contentType, List parts) {
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, 0)
            exchange.responseBody.withStream { out ->
                parts.each {
                    out.write(it instanceof byte[] ? it as byte[] : it.toString().getBytes("UTF-8"))
                    out.flush()
                }
            }
            exchange.close()
        }
    }
}
