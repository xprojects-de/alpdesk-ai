package de.alpdesk.services.task;

import java.util.Map;

/**
 * What a step gave: its structured result (TaskResults) and the tokens its provider calls took.
 *
 * @param mailFailed a mail of the step could not be sent - the run goes on, the run is marked
 */
public record StepOutcome(Map<String, Object> result, boolean mailFailed) {

    public static StepOutcome of(Map<String, Object> result) {
        return new StepOutcome(result, false);
    }
}
