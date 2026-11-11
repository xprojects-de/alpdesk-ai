package de.alpdesk.dto.task;

import de.alpdesk.services.task.TaskError;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * A draft - never saved: its text, what the check found left (none when valid), its steps for the graph, and how many
 * rounds of the model it took.
 */
@Serdeable
public record TaskDraftResponse(String source, boolean valid, List<TaskError> errors, List<TaskStepView> steps,
                                int rounds) {
}
