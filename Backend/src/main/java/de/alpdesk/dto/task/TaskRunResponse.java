package de.alpdesk.dto.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A run with what each of its steps did, the steps of the definition it ran with (for the graph), and what its prompt
 * steps answered in each round.
 */
@Serdeable
public record TaskRunResponse(TaskRunSummary run, List<Step> steps, List<TaskStepView> definition,
                              List<Answer> answers, List<Row> rows) {

    /**
     * A row an extract step gave for one document.
     *
     * @param values  the fields' values by name - dates as YYYY-MM-DD, numbers as numbers
     * @param unclear why it needs a person's look; null: clear
     */
    @Serdeable
    public record Row(String stepId, String source, Map<String, Object> values, @Nullable String unclear) {
    }

    /** @param choice the word picked from the step's choices; null: it has none */
    @Serdeable
    public record Answer(String stepId, int round, @Nullable String choice, String text) {
    }

    /**
     * @param state  DONE, SKIPPED or FAILED
     * @param result the step's structured result - counts, check points with their evidence lines, files read
     * @param cost   what the round's calls cost, as the usage recorded it (a round from before: at the catalog's current
     *               price); null: no model asked, or a round from before whose model is gone
     */
    @Serdeable
    public record Step(String stepId, int round, String state, @Nullable Map<String, Object> result,
                       @Nullable String errorCode, long inputTokens, long outputTokens, long cacheReadTokens,
                       long cacheWriteTokens, Instant startedAt, Instant endedAt, @Nullable BigDecimal cost) {
    }
}
