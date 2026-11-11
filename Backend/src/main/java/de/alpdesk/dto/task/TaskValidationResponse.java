package de.alpdesk.dto.task;

import de.alpdesk.services.task.TaskError;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** What the check of a task's text found: its mistakes with their lines, or its steps for the graph. */
@Serdeable
public record TaskValidationResponse(boolean valid, List<TaskError> errors, List<TaskStepView> steps) {
}
