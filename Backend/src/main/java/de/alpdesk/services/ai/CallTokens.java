package de.alpdesk.services.ai;

import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.bedrock.BedrockTokenUsage;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import io.micronaut.core.annotation.Nullable;

/**
 * A model call's tokens as the provider bills them (docs/architecture/ai-features.md "Prices"): input at the input
 * price, the tokens read from and written to its cache at theirs, output. The three input parts together are the
 * prompt's length, which decides a price tier. The providers count differently: Anthropic's and Bedrock's input
 * tokens leave the cached ones out, OpenAI's and Gemini's include them - taken off here, so input is never billed twice.
 *
 * @param input null: the provider reported no count (some embedding APIs)
 */
public record CallTokens(@Nullable Integer input, int output, int cacheRead, int cacheWrite) {

    public static final CallTokens NONE = new CallTokens(null, 0, 0, 0);

    public static CallTokens of(@Nullable TokenUsage usage) {
        if (usage == null) {
            return NONE;
        }
        int output = orZero(usage.outputTokenCount());
        return switch (usage) {
            case AnthropicTokenUsage anthropic -> new CallTokens(usage.inputTokenCount(), output,
                    orZero(anthropic.cacheReadInputTokens()), orZero(anthropic.cacheCreationInputTokens()));
            case BedrockTokenUsage bedrock -> new CallTokens(usage.inputTokenCount(), output,
                    orZero(bedrock.cacheReadInputTokens()), orZero(bedrock.cacheWriteInputTokens()));
            case OpenAiTokenUsage openAi when openAi.inputTokensDetails() != null -> {
                int read = orZero(openAi.inputTokensDetails().cachedTokens());
                int write = orZero(openAi.inputTokensDetails().cacheWriteTokens());
                yield new CallTokens(without(usage.inputTokenCount(), read + write), output, read, write);
            }
            case GoogleAiGeminiTokenUsage gemini -> {
                int read = orZero(gemini.cachedContentTokenCount());
                // Gemini reports its thinking apart from the answer; Google bills both at the output price
                int thoughts = orZero(gemini.thoughtsTokenCount());
                yield new CallTokens(without(usage.inputTokenCount(), read), output + thoughts, read, 0);
            }
            default -> new CallTokens(usage.inputTokenCount(), output, 0, 0);
        };
    }

    /** Tokens without a cache - a run's step from before cache tokens were kept. */
    public static CallTokens plain(long input, long output) {
        return new CallTokens(Math.toIntExact(input), Math.toIntExact(output), 0, 0);
    }

    /** What decides a price tier: everything that went in, cached or not. */
    public long promptLength() {
        return (long) orZero(input) + cacheRead + cacheWrite;
    }

    private static int orZero(@Nullable Integer count) {
        return count == null ? 0 : count;
    }

    @Nullable
    private static Integer without(@Nullable Integer input, int cached) {
        return input == null ? null : Math.max(0, input - cached);
    }
}
