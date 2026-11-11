package de.alpdesk.entities.document;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/**
 * An area of a tenant's knowledge ("Vertrieb", "Werkstatt"): its documents are found only by the users and keys assigned
 * to it, and by the tenant admins. Management database - unlike the documents themselves, which live in the tenant's.
 */
@MappedEntity("knowledge_area")
public record KnowledgeArea(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        String name,

        Instant createdAt
) {
}
