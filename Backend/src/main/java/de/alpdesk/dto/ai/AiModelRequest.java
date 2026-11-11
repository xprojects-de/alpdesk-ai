package de.alpdesk.dto.ai;

import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.ai.ModelKind;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;

@Serdeable
public record AiModelRequest(
        AiProvider provider,
        ModelKind kind,
        /** The provider's name of the model, e.g. "claude-sonnet-5-5". */
        String modelName,
        String displayName,
        /** Required for an embedding model, not allowed for a chat model. */
        @Nullable Integer dimension,
        /** Per million input tokens. */
        BigDecimal inputPrice,
        /** Per million output tokens (0 for an embedding model). */
        BigDecimal outputPrice,
        /** Left out on create = true, on update = unchanged. */
        @Nullable Boolean active,
        /** Chat models: what it reads as it is. Left out on create = false, on update = unchanged. */
        @Nullable Boolean readsPdf,
        @Nullable Boolean readsAudio,
        /** Whether it writes the transcripts of uploaded audio (one model at most; it must read audio). */
        @Nullable Boolean transcribes,
        /** Whether it reads the images and scans of the company's documents (one chat model at most). */
        @Nullable Boolean readsScans,
        /** Chat models: whether it takes a temperature. Left out on create = true, on update = unchanged. */
        @Nullable Boolean takesTemperature,
        /** Chat models: whether its reasoning effort is set. Left out on create = false, on update = unchanged. */
        @Nullable Boolean steersReasoning,
        /** Chat models: whether it calls tools reliably. Left out on create = true, on update = unchanged. */
        @Nullable Boolean callsTools,
        /** Models on own servers only, required there: the server's address (https, or http for an allowed host). */
        @Nullable String baseUrl,
        /**
         * Models on own servers only: the server's key - for BEDROCK an IAM access key, "ACCESS_KEY_ID:SECRET", required.
         * Left out = unchanged; a new host drops the old key.
         */
        @Nullable String apiKey,
        /** True removes the server's key. */
        @Nullable Boolean removeApiKey,
        /** OLLAMA chat models only: the context length; left out = the default. */
        @Nullable Integer contextLength,
        /** Chat models only: the most tokens one answer may take, reasoning included; left out = the default. */
        @Nullable Integer maxOutputTokens,
        /** A request with a longer prompt pays the tier prices; left out = one price (no tier). */
        @Nullable Integer tierThreshold,
        /** Required with a threshold, not allowed without one. */
        @Nullable BigDecimal tierInputPrice,
        @Nullable BigDecimal tierOutputPrice,
        /** A token read from or written to the provider's cache; left out = like an input token. */
        @Nullable BigDecimal cacheReadPrice,
        @Nullable BigDecimal cacheWritePrice,
        /** The cache prices above the threshold (only with one); left out = like an input token above it. */
        @Nullable BigDecimal tierCacheReadPrice,
        @Nullable BigDecimal tierCacheWritePrice
) {
}
