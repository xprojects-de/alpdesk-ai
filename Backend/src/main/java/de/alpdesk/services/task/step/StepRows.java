package de.alpdesk.services.task.step;

import de.alpdesk.entities.task.RunRow;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.TaskDefinition.Field;
import de.alpdesk.services.task.TaskDefinition.Step;
import de.alpdesk.services.task.TaskResults;
import io.micronaut.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The rows an earlier step of this run gave (extract, a call_tool source), with their values read back. */
final class StepRows {

    private StepRows() {
    }

    /** A row with its values by the fields' names. */
    record Row(RunRow row, Map<String, Object> values) {
    }

    /** The fields of that step's rows - an extract step's, or a call_tool source's. */
    static List<Field> fields(StepContext context, String step) {
        Step input = context.task().step(step);
        if (input.extract() != null) {
            return input.extract().fields();
        }
        return input.callTool() != null && input.callTool().fields() != null ? input.callTool().fields() : List.of();
    }

    /** The rows of that step in their order - none when it gave none (skipped, nothing read). */
    static List<Row> of(TaskRunRepository runs, JsonMapper json, StepContext context, String step) {
        Map<String, Object> result = context.results().get(step);
        Set<Long> ids = new HashSet<>();
        if (result != null && result.get(TaskResults.ROW_IDS) instanceof List<?> rowIds) {
            rowIds.forEach(id -> ids.add(((Number) id).longValue()));
        }
        List<Row> rows = new ArrayList<>();
        if (ids.isEmpty()) {
            return rows;
        }
        for (RunRow row : runs.findRows(context.dataSource(), context.runId())) {
            if (ids.contains(row.id())) {
                rows.add(new Row(row, values(json, row.values())));
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(JsonMapper json, String values) {
        try {
            return json.readValue(values, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
