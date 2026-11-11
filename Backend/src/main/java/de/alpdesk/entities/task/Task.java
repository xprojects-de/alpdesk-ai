package de.alpdesk.entities.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/**
 * A scheduled task of a tenant (docs/architecture/tasks.md), management database: the text as its tenant admin wrote it
 * and the compiled definition the engine runs. Its runs live in the tenant database.
 */
@MappedEntity("task")
public record Task(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        String name,

        /** As written - YAML or JSON, comments kept. */
        String source,

        /** The compiled definition (services.task.TaskDefinition) as JSON. */
        String definition,

        /** Raised by every save. */
        int version,

        boolean active,

        /** When its schedule is due next; null: it has none (or it is switched off). */
        @Nullable
        Instant nextRunAt,

        Instant createdAt,

        Instant updatedAt
) {
}
