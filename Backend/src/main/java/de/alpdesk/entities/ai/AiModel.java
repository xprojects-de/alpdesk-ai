package de.alpdesk.entities.ai;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.math.BigDecimal;
import java.time.Instant;

@MappedEntity("ai_model")
public record AiModel(

        @Id
        @GeneratedValue
        Long id,

        AiProvider provider,

        ModelKind kind,

        /** The provider's name of the model, as its API expects it. */
        String modelName,

        String displayName,

        /** The vector length of an embedding model; null for a chat model. */
        @Nullable
        Integer dimension,

        /** Per million input tokens, in the currency the admin bills. */
        BigDecimal inputPrice,

        /** Per million output tokens. */
        BigDecimal outputPrice,

        boolean active,

        /** Chat models: whether it reads a PDF as it is (images every chat model reads). */
        boolean readsPdf,

        boolean readsAudio,

        /** The one chat model (reading audio) that writes the transcripts of uploaded audio files. */
        boolean transcribes,

        /** The one chat model that reads the images and scanned pages of the company's documents. */
        boolean readsScans,

        /** Chat models: whether it takes a temperature (the tenant's creativity) - reasoning models refuse one. */
        boolean takesTemperature,

        /** Chat models: whether its reasoning effort is set (the tenant's "Nachdenken"). */
        boolean steersReasoning,

        /** Chat models: whether it calls tools reliably - without, the chat searches and reads files for it beforehand. */
        boolean callsTools,

        /**
         * Models on own servers: the server's address, e.g. "https://models.example.com/v1" - Bedrock's names its region,
         * "https://bedrock-runtime.eu-central-1.amazonaws.com".
         */
        @Nullable
        String baseUrl,

        /** The server's key, encrypted (security.SecretCipher) - never sent out; null when it asks for none. */
        @Nullable
        String apiKey,

        /** The key's last characters, to recognise it. */
        @Nullable
        String keyHint,

        /** OLLAMA chat models: the context length (num_ctx); null = services.ai.AiModels' default. */
        @Nullable
        Integer contextLength,

        /** Chat models: the most tokens one answer may take, reasoning included; null = app.ai.max-output-tokens. */
        @Nullable
        Integer maxOutputTokens,

        /** A request whose prompt (input and cached tokens) is longer than this pays the tier prices; null: no tier. */
        @Nullable
        Integer tierThreshold,

        /** Per million input tokens above the threshold. */
        @Nullable
        BigDecimal tierInputPrice,

        /** Per million output tokens above the threshold. */
        @Nullable
        BigDecimal tierOutputPrice,

        /** Per million tokens read from the provider's cache; null: like an input token. */
        @Nullable
        BigDecimal cacheReadPrice,

        /** Per million tokens written to the provider's cache; null: like an input token. */
        @Nullable
        BigDecimal cacheWritePrice,

        /** The cache prices above the threshold; null: like an input token above it. */
        @Nullable
        BigDecimal tierCacheReadPrice,

        @Nullable
        BigDecimal tierCacheWritePrice,

        Instant createdAt,

        Instant updatedAt
) {
}
