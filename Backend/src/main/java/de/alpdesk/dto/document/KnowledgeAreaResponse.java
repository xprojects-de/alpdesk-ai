package de.alpdesk.dto.document;

import de.alpdesk.entities.document.KnowledgeArea;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record KnowledgeAreaResponse(Long id, String name, Instant createdAt) {

    public static KnowledgeAreaResponse from(KnowledgeArea area) {
        return new KnowledgeAreaResponse(area.id(), area.name(), area.createdAt());
    }
}
