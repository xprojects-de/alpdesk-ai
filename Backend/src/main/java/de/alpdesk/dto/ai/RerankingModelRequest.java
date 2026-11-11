package de.alpdesk.dto.ai;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** The reranker of the knowledge search; null switches reranking off. */
@Serdeable
public record RerankingModelRequest(@Nullable Long modelId) {
}
