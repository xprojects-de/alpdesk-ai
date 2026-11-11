package de.alpdesk.dto.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Map;

/**
 * One step of a task as the graph beside its text shows it.
 *
 * @param type  read_files, agent, verify or send_mail
 * @param line  where it begins in the text
 * @param when  its condition as written; null: none
 * @param details what the step works with, by type - the frontend writes its line from them in its language:
 *                read_files store, pattern, onlyNew; agent model, checks (their number); verify of; send_mail to
 *                (comma separated, empty: the notify list)
 */
@Serdeable
public record TaskStepView(String id, String type, int line, List<String> input, @Nullable String when,
                           List<Edge> next, Map<String, String> details) {

    /** @param when null: always (the else) */
    @Serdeable
    public record Edge(@Nullable String when, String to, @Nullable Integer maxRounds) {
    }
}
