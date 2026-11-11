package de.alpdesk.services.ai

import de.alpdesk.entities.ai.AiModel
import de.alpdesk.entities.ai.AiProvider
import de.alpdesk.entities.ai.ModelKind
import de.alpdesk.entities.assistant.Creativity
import de.alpdesk.entities.assistant.Reasoning
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ToolChoice
import spock.lang.Specification

import java.time.Instant

/**
 * What a call sends of the tenant's settings to the providers that cannot be reached in a spec (Anthropic, OpenAI,
 * Gemini): AiModels.Sent per provider and catalog entry. Own servers are checked over HTTP in ModelServerSpec.
 */
class SentSpec extends Specification {

    def "Claude thinks without a temperature; with reasoning off it gets one and no thinking: #provider"() {
        when:
        def thinking = AiModels.Sent.of(model(provider), new ChatOptions(Creativity.HIGH, Reasoning.MEDIUM), 100)
        def off = AiModels.Sent.of(model(provider), new ChatOptions(Creativity.HIGH, Reasoning.OFF), 100)

        then:
        thinking.temperature() == null
        thinking.anthropicThinking() == "enabled"
        thinking.thinkingBudget() == 8192
        thinking.keepsThinking()
        thinking.maxTokens() == 100 + 8192
        off.temperature() == 1.0d
        off.anthropicThinking() == null
        off.maxTokens() == 100

        where:
        provider << [AiProvider.ANTHROPIC, AiProvider.BEDROCK]
    }

    def "OpenAI's efforts: #reasoning sends #effort"() {
        expect:
        AiModels.Sent.of(model(AiProvider.OPENAI), new ChatOptions(null, reasoning), 100).openAiEffort() == effort

        where:
        reasoning        || effort
        Reasoning.OFF    || "minimal"
        Reasoning.LOW    || "low"
        Reasoning.MEDIUM || "medium"
        Reasoning.HIGH   || "high"
        null             || null
    }

    def "Gemini gets a thinking budget - 0 switches it off"() {
        expect:
        AiModels.Sent.of(model(AiProvider.GEMINI), new ChatOptions(null, Reasoning.OFF), 100).geminiThinking().thinkingBudget() == 0
        AiModels.Sent.of(model(AiProvider.GEMINI), new ChatOptions(null, Reasoning.HIGH), 100).geminiThinking().thinkingBudget() == 16384
    }

    def "what the catalog says a model does not take is not sent"() {
        when:
        def sent = AiModels.Sent.of(model(AiProvider.GEMINI, false, false), new ChatOptions(Creativity.LOW, Reasoning.HIGH), 100)

        then:
        sent.temperature() == null
        sent.geminiThinking() == null
        sent.maxTokens() == 100
    }

    def "nothing asked, nothing sent"() {
        when:
        def sent = AiModels.Sent.of(model(AiProvider.OPENAI), ChatOptions.NONE, 100)

        then:
        sent.temperature() == null
        sent.openAiEffort() == null
        sent.maxTokens() == 100
    }

    def "the catalog's output limit wins over the installation's; a thinking budget comes on top"() {
        expect:
        AiModels.Sent.of(model(AiProvider.OPENAI, true, false, 32768), ChatOptions.NONE, 16384).maxTokens() == 32768
        AiModels.Sent.of(model(AiProvider.OPENAI, true, false, null), ChatOptions.NONE, 16384).maxTokens() == 16384
        AiModels.Sent.of(model(AiProvider.ANTHROPIC, true, true, 32768), new ChatOptions(null, Reasoning.HIGH), 16384)
                .maxTokens() == 32768 + 16384
    }

    def "Gemini shows its thoughts while it thinks - not when it does not"() {
        expect:
        AiModels.Sent.of(model(AiProvider.GEMINI), new ChatOptions(null, Reasoning.HIGH), 100).geminiThinkingShown().includeThoughts()
        AiModels.Sent.of(model(AiProvider.GEMINI), new ChatOptions(null, Reasoning.OFF), 100).geminiThinkingShown().includeThoughts() == null
        AiModels.Sent.of(model(AiProvider.GEMINI), ChatOptions.NONE, 100).geminiThinkingShown() == null
    }

    def "the last round lists the tools without letting one be called - Claude refuses tool calls in the history without them: #provider"() {
        given:
        def tools = [ToolSpecification.builder().name("similarity_search").build()]
        def messages = [UserMessage.from("Frage")]

        when:
        def last = AiModels.request(model(provider), messages, tools, false)
        def other = AiModels.request(model(provider), messages, tools, true)

        then:
        last.toolSpecifications()*.name() == listed
        last.toolChoice() == choice
        other.toolSpecifications()*.name() == ["similarity_search"]
        other.toolChoice() == null

        where:
        provider                     || listed                || choice
        AiProvider.ANTHROPIC         || ["similarity_search"] || ToolChoice.NONE
        AiProvider.OPENAI            || ["similarity_search"] || ToolChoice.NONE
        AiProvider.GEMINI            || ["similarity_search"] || ToolChoice.NONE
        // langchain4j's Ollama client refuses a tool choice; servers differ in what they take.
        AiProvider.OLLAMA            || []                    || null
        AiProvider.OPENAI_COMPATIBLE || []                    || null
        // Claude on Bedrock refuses them all the same; langchain4j's Bedrock client leaves the choice NONE out.
        AiProvider.BEDROCK           || ["similarity_search"] || ToolChoice.NONE
    }

    private static AiModel model(AiProvider provider, boolean takesTemperature = true, boolean steersReasoning = true,
                                 Integer maxOutputTokens = null) {
        new AiModel(1L, provider, ModelKind.CHAT, "m", "M", null, BigDecimal.ONE, BigDecimal.ONE, true, false, false,
                false, false, takesTemperature, steersReasoning, true, null, null, null, null, maxOutputTokens,
                null, null, null, null, null, null, null, Instant.now(), Instant.now())
    }
}
