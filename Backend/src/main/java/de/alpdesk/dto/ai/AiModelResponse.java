package de.alpdesk.dto.ai;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.ai.ModelKind;
import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;
import java.time.Instant;

@Serdeable
public record AiModelResponse(
        Long id,
        AiProvider provider,
        ModelKind kind,
        String modelName,
        String displayName,
        Integer dimension,
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        boolean active,
        boolean readsPdf,
        boolean readsAudio,
        boolean transcribes,
        boolean readsScans,
        boolean takesTemperature,
        boolean steersReasoning,
        boolean callsTools,
        String baseUrl,
        /** The server key's last characters; null without a key. The key itself never goes out. */
        String keyHint,
        Integer contextLength,
        Integer maxOutputTokens,
        Integer tierThreshold,
        BigDecimal tierInputPrice,
        BigDecimal tierOutputPrice,
        BigDecimal cacheReadPrice,
        BigDecimal cacheWritePrice,
        BigDecimal tierCacheReadPrice,
        BigDecimal tierCacheWritePrice,
        Instant createdAt,
        Instant updatedAt
) {
    public static AiModelResponse from(AiModel model) {
        return new AiModelResponse(model.id(), model.provider(), model.kind(), model.modelName(), model.displayName(),
                model.dimension(), model.inputPrice(), model.outputPrice(), model.active(), model.readsPdf(),
                model.readsAudio(), model.transcribes(), model.readsScans(), model.takesTemperature(),
                model.steersReasoning(), model.callsTools(), model.baseUrl(),
                model.keyHint(), model.contextLength(), model.maxOutputTokens(), model.tierThreshold(),
                model.tierInputPrice(), model.tierOutputPrice(), model.cacheReadPrice(), model.cacheWritePrice(),
                model.tierCacheReadPrice(), model.tierCacheWritePrice(), model.createdAt(), model.updatedAt());
    }
}
