package de.alpdesk.services.ai

import de.alpdesk.entities.ai.AiModel
import de.alpdesk.entities.ai.AiProvider
import de.alpdesk.entities.ai.ModelKind
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.anthropic.AnthropicChatRequestParameters
import dev.langchain4j.model.anthropic.AnthropicTokenUsage
import dev.langchain4j.model.bedrock.BedrockCachePointPlacement
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters
import dev.langchain4j.model.bedrock.BedrockTokenUsage
import dev.langchain4j.model.chat.request.ToolChoice
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage
import dev.langchain4j.model.openai.OpenAiChatRequestParameters
import dev.langchain4j.model.openai.OpenAiTokenUsage
import dev.langchain4j.model.output.TokenUsage
import spock.lang.Specification

import java.time.Instant

/**
 * What a call costs as the providers bill it (docs/architecture/ai-features.md "Prices", "Prompt caching"): a price tier
 * by the prompt's length, cached tokens at their own price, each provider's way of counting them - and which requests
 * ask the providers to cache. No provider is called: Anthropic, OpenAI and Gemini cannot be reached in a spec.
 */
class PricesAndCacheSpec extends Specification {

    /** Claude Haiku 5.5's prices of October 2026, per million tokens. */
    static final AiModel HAIKU = model(AiProvider.ANTHROPIC, 0.10, 0.50, 100_000, 0.50, 2.50, 0.01, 0.125, 0.05, 0.625)

    def "a request up to the threshold pays the lower prices, one above it the tier's - for all of its tokens: #request"() {
        expect:
        UsageService.amount(HAIKU, new CallTokens(input, output, read, write)) == expected

        where:
        request                                 | input   | output | read   | write  || expected
        "small, no cache"                       | 10_000  | 1_000  | 0      | 0      || 0.0015
        "just at the threshold"                 | 100_000 | 1_000  | 0      | 0      || 0.0105
        "one token above"                       | 100_001 | 1_000  | 0      | 0      || 0.052501
        "cached tokens count into the length"   | 10_000  | 1_000  | 95_000 | 0      || 0.01225
        "small with cache"                      | 1_000   | 1_000  | 30_000 | 10_000 || 0.00215
    }

    def "a cache price not set is the input price of the call's tier - without any tier one price, as before"() {
        given:
        def noCachePrices = model(AiProvider.ANTHROPIC, 0.10, 0.50, 100_000, 0.50, 2.50, null, null, null, null)
        def plain = model(AiProvider.OPENAI, 3, 15, null, null, null, null, null, null, null)

        expect:
        UsageService.amount(noCachePrices, new CallTokens(1_000, 0, 9_000, 0)) == 0.001
        UsageService.amount(noCachePrices, new CallTokens(1_000, 0, 200_000, 0)) == 0.1005
        UsageService.amount(plain, CallTokens.plain(1_000_000, 100_000)) == 4.5
        UsageService.amount(plain, new CallTokens(1_000_000, 100_000, 1_000_000, 0)) == 7.5
    }

    def "the summed tokens of several calls are priced below the threshold - which call was above it is lost"() {
        expect: "1 500 000 tokens of a step from before - each of its calls may have been small"
        UsageService.amountOfSeveralCalls(HAIKU, 1_500_000, 50_000) == 0.175
    }

    def "every provider's tokens become input, output, cache read and write - nothing billed twice"() {
        expect: "Anthropic leaves the cached tokens out of its input already"
        CallTokens.of(AnthropicTokenUsage.builder().inputTokenCount(120).outputTokenCount(40)
                .cacheReadInputTokens(9_000).cacheCreationInputTokens(500).build()) == new CallTokens(120, 40, 9_000, 500)

        and: "so does Bedrock"
        CallTokens.of(BedrockTokenUsage.builder().inputTokenCount(120).outputTokenCount(40)
                .cacheReadInputTokens(9_000).cacheWriteInputTokens(500).build()) == new CallTokens(120, 40, 9_000, 500)

        and: "OpenAI counts them in its input - taken off"
        CallTokens.of(OpenAiTokenUsage.builder().inputTokenCount(10_000).outputTokenCount(40)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(8_000).build())
                .build()) == new CallTokens(2_000, 40, 8_000, 0)

        and: "so does Gemini - and its thinking, reported apart from the answer, counts as output"
        CallTokens.of(GoogleAiGeminiTokenUsage.builder().inputTokenCount(10_000).outputTokenCount(40)
                .cachedContentTokenCount(6_000).build()) == new CallTokens(4_000, 40, 6_000, 0)
        CallTokens.of(GoogleAiGeminiTokenUsage.builder().inputTokenCount(10_000).outputTokenCount(40)
                .thoughtsTokenCount(1_500).build()) == new CallTokens(10_000, 1_540, 0, 0)

        and: "an own server's or an answer without cache figures stays as it is; none at all is no count"
        CallTokens.of(new TokenUsage(300, 20)) == new CallTokens(300, 20, 0, 0)
        CallTokens.of(OpenAiTokenUsage.builder().inputTokenCount(300).outputTokenCount(20).build()) == new CallTokens(300, 20, 0, 0)
        CallTokens.of(null) == CallTokens.NONE
    }

    def "only the agent loop's rounds ask to be cached - Anthropic caches the conversation, OpenAI gets the key"() {
        given:
        def messages = [UserMessage.from("Hallo?")]
        def tools = [ToolSpecification.builder().name("search_documents").description("Sucht").build()]

        when:
        def anthropic = AiModels.request(HAIKU, messages, tools, true, "answer-1").parameters()
        def openAi = AiModels.request(model(AiProvider.OPENAI, 1, 1), messages, tools, true, "answer-2").parameters()
        def single = AiModels.request(HAIKU, messages, [], true).parameters()
        def lastRound = AiModels.request(HAIKU, messages, tools, false, "answer-1").parameters()

        then: "the loop's rounds"
        (anthropic as AnthropicChatRequestParameters).cacheAutomatically()
        anthropic.toolSpecifications()*.name() == ["search_documents"]
        (openAi as OpenAiChatRequestParameters).promptCacheKey() == "answer-2"

        and: "a single call is not cached"
        !(single instanceof AnthropicChatRequestParameters)
        single.toolSpecifications().isEmpty()

        and: "the last round lists the tools without letting them be called, cached like the others"
        (lastRound as AnthropicChatRequestParameters).cacheAutomatically()
        lastRound.toolChoice() == ToolChoice.NONE
    }

    def "Bedrock's rounds are cached up to the question - tools, instructions, history and question are what they share"() {
        when:
        def round = AiModels.request(model(AiProvider.BEDROCK, 1, 1), [UserMessage.from("Hallo?")], [], true, "answer-4")
                .parameters()
        def single = AiModels.request(model(AiProvider.BEDROCK, 1, 1), [UserMessage.from("Hallo?")], [], true).parameters()

        then:
        (round as BedrockChatRequestParameters).cachePointPlacement() == BedrockCachePointPlacement.AFTER_LAST_USER_MESSAGE
        !(single instanceof BedrockChatRequestParameters)
    }

    def "Gemini and own servers get no cache settings - they cache by themselves"() {
        when:
        def parameters = AiModels.request(model(provider, 1, 1), [UserMessage.from("Hallo?")], [], true, "answer-3")
                .parameters()

        then:
        !(parameters instanceof AnthropicChatRequestParameters)
        !(parameters instanceof OpenAiChatRequestParameters)

        where:
        provider << [AiProvider.GEMINI, AiProvider.OLLAMA, AiProvider.OPENAI_COMPATIBLE]
    }

    private static AiModel model(AiProvider provider, BigDecimal input, BigDecimal output, Integer threshold = null,
                                 BigDecimal tierInput = null, BigDecimal tierOutput = null, BigDecimal cacheRead = null,
                                 BigDecimal cacheWrite = null, BigDecimal tierCacheRead = null,
                                 BigDecimal tierCacheWrite = null) {
        new AiModel(1L, provider, ModelKind.CHAT, "m", "M", null, input, output, true, false, false, false, false, true,
                false, true, null, null, null, null, null, threshold, tierInput, tierOutput, cacheRead, cacheWrite,
                tierCacheRead, tierCacheWrite, Instant.now(), Instant.now())
    }
}
