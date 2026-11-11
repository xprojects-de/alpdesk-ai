package de.alpdesk.entities.a2a;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/**
 * Which tenant and MCP key an A2A task of the tenant's assistant belongs to (management database): the SDK names a task
 * by a random id and asks for it without its caller; the task itself is in the tenant's database (a2a_task).
 */
@MappedEntity("a2a_task_index")
public record A2aTaskIndex(

        @Id
        String taskId,

        Long tenantId,

        Long mcpKeyId,

        Instant createdAt
) {
}
