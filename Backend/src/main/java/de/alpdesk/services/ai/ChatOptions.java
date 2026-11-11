package de.alpdesk.services.ai;

import de.alpdesk.entities.assistant.Creativity;
import de.alpdesk.entities.assistant.Reasoning;
import io.micronaut.core.annotation.Nullable;

/**
 * What the tenant's assistant settings ask of a chat call (services.assistant.AssistantService); null: the model's
 * default. AiModels sends only what the catalog says the model takes.
 *
 * @param cacheKey the rounds of one answer of the agent loop share it: Anthropic caches what they have in common,
 *                 OpenAI routes them to one cache (docs/architecture/ai-features.md "Prompt caching"); null: a single
 *                 call, nothing cached - a write costs more than an input token and would never be read
 */
public record ChatOptions(@Nullable Creativity creativity, @Nullable Reasoning reasoning, @Nullable String cacheKey) {

    /** Nothing asked - transcripts, reading scans. */
    public static final ChatOptions NONE = new ChatOptions(null, null);

    public ChatOptions(@Nullable Creativity creativity, @Nullable Reasoning reasoning) {
        this(creativity, reasoning, null);
    }

    public ChatOptions withCacheKey(String key) {
        return new ChatOptions(creativity, reasoning, key);
    }
}
