package de.alpdesk.dto.api.v1;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Serdeable
@Schema(description = "A run of a scheduled task and what it found")
public record ApiTaskRunResponse(
        Long id,
        @Schema(description = "QUEUED, RUNNING, DONE (ran through), UNCLEAR (a finding could not be settled), FAILED or CANCELLED")
        String state,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant endedAt,
        @Schema(description = "Why a FAILED run failed: a stable code (e.g. fileStore.unreachable) and the English message")
        @Nullable String errorCode,
        @Nullable String errorMessage,
        @Schema(description = "The check points the run did not find ok - empty while it runs and when all was fine")
        List<Finding> findings,
        @Schema(description = "What the run hands on: the last answer of each prompt step the task mails - empty while it runs")
        List<Answer> answers,
        @Schema(description = "The rows the run's extract steps took out of documents, one per document - empty while it runs")
        List<Row> rows
) {

    @Serdeable
    @Schema(description = "A row an extract step took out of one document")
    public record Row(
            @Schema(description = "The step's id in the task", example = "rows")
            String step,
            @Schema(description = "The document's path and the checksum of its content - the same document gives the "
                    + "same, a changed one another", example = "2026/03/invoice-1001.pdf#3f2a9c01be47")
            String source,
            @Schema(description = "The fields' values by name - dates as YYYY-MM-DD, amounts, numbers and percentages "
                    + "as numbers; a field the document did not show is left out")
            Map<String, Object> values,
            @Schema(description = "Why the row needs a person's look; null: clear")
            @Nullable String unclear
    ) {
    }

    @Serdeable
    @Schema(description = "A prompt step's answer the task hands on")
    public record Answer(
            @Schema(description = "The step's id in the task", example = "summary")
            String step,
            @Schema(description = "The word the step picked from its choices; null: it has none")
            @Nullable String choice,
            String text
    ) {
    }

    @Serdeable
    @Schema(description = "A check point of the task that the run did not find ok")
    public record Finding(
            @Schema(description = "The check point's name", example = "Interface")
            String checkPoint,
            @Schema(description = "ERROR, WARNING or REPORT (something to hand on, not a problem) - as the task "
                    + "weighs the check point")
            String severity,
            @Schema(description = "finding (shown by the lines) or unclear (could not be settled)")
            String status,
            @Schema(description = "What the model noted")
            String note,
            @Schema(description = "The lines of the files that show it")
            List<Evidence> evidence
    ) {
    }

    @Serdeable
    public record Evidence(String file, long line, String text) {
    }
}
