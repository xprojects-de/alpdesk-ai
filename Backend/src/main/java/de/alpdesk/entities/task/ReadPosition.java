package de.alpdesk.entities.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/**
 * How far a task has read one file of a store (tenant database): the byte after the last complete line read, and
 * that line's number. Kept in a run's state until the run succeeded - only then it moves on.
 */
@Serdeable
public record ReadPosition(Long storeId, String path, long byteOffset, long lineNumber, @Nullable Instant modifiedAt) {
}
