package de.alpdesk.services.task.step;

import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.DocumentService;
import de.alpdesk.services.mcp.McpClient;
import de.alpdesk.services.mcp.McpConnectionService;
import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.StepOutcome;
import de.alpdesk.services.task.TaskDefinition.CallTool;
import de.alpdesk.services.task.TaskDefinition.Field;
import de.alpdesk.services.task.TaskCompiler;
import de.alpdesk.services.task.TaskResults;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * call_tool (docs/architecture/tasks.md "call_tool"). As a target, the rows of its input sent to a tool of a connected
 * software - one call per row, or one with every row. As a source (no input), its reading tool lists objects: rows of
 * typed fields, or documents fetched one by one with the file tool - {lastRun} in its arguments is the task's last
 * successful run, its "only new". A row that is unclear is never sent; it waits for a person. One call failing (the
 * tool answers an error) marks its row and the others are still sent - the run then ends unclear and mails the rows
 * that failed; the software out of reach marks every row still to go with that reason, unsent. Recognising a repeat is
 * the software's matter: every call carries the row's source (the document's path and checksum), so a row sent again
 * after a failed run is known there.
 * Not in a transaction: the software may take seconds per call.
 */
@Singleton
public class CallToolStep {

    /** A tool's answer kept per call at most - shown in the run view, not more. */
    static final int MAX_ANSWER = 2000;
    /** The objects a source's tool may list in one answer - its text is cut at McpClient.MAX_RESULT_TEXT anyway. */
    static final int MAX_SOURCE_ROWS = 200;
    /** What a file tool's failure for one file is - that file's fault, not the software's: an error, too large. */
    private static final Set<String> FILE_REFUSED = Set.of("mcpServer.error", "mcpServer.tooLarge");
    /** What a target's failure for one row is - that row's fault: an error, too large, a value not accepted. */
    private static final Set<String> ROW_REFUSED = Set.of("mcpServer.error", "mcpServer.tooLarge",
            "mcpServer.requestFailed");

    private final McpConnectionService connections;
    private final TaskRunRepository runs;
    private final DocumentReader reader;
    private final JsonMapper json;
    private final ZoneId zone;

    public CallToolStep(McpConnectionService connections, TaskRunRepository runs, DocumentReader reader,
                        JsonMapper json, @Value("${app.timezone}") String zone) {
        this.connections = connections;
        this.runs = runs;
        this.reader = reader;
        this.json = json;
        this.zone = ZoneId.of(zone);
    }

    public StepOutcome run(StepContext context) {
        if (context.step().input().isEmpty()) {
            return source(context);
        }
        CallTool settings = context.step().callTool();
        String input = context.step().input().getFirst();
        List<Field> fields = StepRows.fields(context, input);
        Map<String, Object> schema = schema(context, settings.tool(), settings);
        List<StepRows.Row> clear = new ArrayList<>();
        for (StepRows.Row row : StepRows.of(runs, json, context, input)) {
            if (row.row().unclear() == null) {
                clear.add(row);
            }
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        if (settings.all() && !clear.isEmpty()) {
            List<Map.Entry<String, Map<String, Object>>> rows = new ArrayList<>();
            clear.forEach(row -> rows.add(new AbstractMap.SimpleEntry<>(row.row().source(), row.values())));
            calls.add(call(context, settings, ToolArguments.forAll(schema, fields, rows, settings.with()),
                    String.valueOf(clear.size()) + " rows").entry());
        } else if (!settings.all()) {
            String softwareDown = null;
            for (StepRows.Row row : clear) {
                if (runs.cancelRequested(context.dataSource(), context.runId())) {
                    throw ServiceException.conflict("task.cancelled", "The run was cancelled");
                }
                if (softwareDown != null) {
                    calls.add(entry(row.row().source(), true, softwareDown));
                    continue;
                }
                Call call = call(context, settings, ToolArguments.forRow(schema, fields, row.values(),
                        row.row().source(), settings.with()), row.row().source());
                calls.add(call.entry());
                softwareDown = call.softwareDown();
            }
        }
        int failed = (int) calls.stream().filter(call -> Boolean.TRUE.equals(call.get("failed"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.SENT, calls.size() - failed);
        result.put(TaskResults.FAILED, failed);
        result.put(TaskResults.CALLS, calls);
        return StepOutcome.of(result);
    }

    /** A call's entry in the step's result; softwareDown the reason when the software as a whole failed, else null. */
    private record Call(Map<String, Object> entry, @Nullable String softwareDown) {
    }

    /**
     * One call and what came of it - a failure is kept with the call, not thrown: the other rows still go. A failure of
     * the software as a whole (out of reach, no answer in time, our key refused) is said too: each further row would
     * fail the same, and only after the timeout - the run's other rows are marked with it, not sent.
     */
    private Call call(StepContext context, CallTool settings, Map<String, Object> arguments, String source) {
        try {
            McpClient.CallResult result = connections.callForTask(context.tenant().id(), settings.connectionId(),
                    settings.tool(), arguments, false);
            return new Call(entry(source, result.error(), result.text()), null);
        } catch (ServiceException e) {
            if (e.code().equals("task.toolUnavailable")) {
                throw e;
            }
            String softwareDown = ROW_REFUSED.contains(e.code()) ? null : e.getMessage();
            return new Call(entry(source, true, e.getMessage()), softwareDown);
        }
    }

    private static Map<String, Object> entry(String source, boolean failed, String answer) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("source", source);
        call.put("failed", failed);
        call.put("answer", cut(answer));
        return call;
    }

    /**
     * The source: one call of its tool with its with ({lastRun} filled in), its answer a JSON array of objects - at most
     * 200. A failure of the tool or of the software fails the step: {lastRun} has not moved on, the next run asks for
     * the same again.
     */
    private StepOutcome source(StepContext context) {
        CallTool settings = context.step().callTool();
        Map<String, String> with = withLastRun(context, settings.with());
        Map<String, Object> arguments = ToolArguments.forSource(schema(context, settings.tool(), settings), with);
        McpClient.CallResult answer = connections.callForTask(context.tenant().id(), settings.connectionId(),
                settings.tool(), arguments, true);
        if (answer.error()) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "task.toolFailed", "The tool " + settings.tool()
                    + " answered with an error: " + cut(answer.text()), Map.of("tool", settings.tool()));
        }
        if (answer.cutOff()) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "task.toolAnswerTooLong", "The tool "
                    + settings.tool() + " answered with more text than a call may hold - let it list fewer at a time",
                    Map.of("tool", settings.tool()));
        }
        List<Map<?, ?>> objects = objects(settings.tool(), answer.text());
        return settings.fields() != null ? rows(context, settings, objects) : documents(context, settings, objects);
    }

    /** Each object a row of the fields, checked by their types - one without a key, or a value not as asked, unclear. */
    private StepOutcome rows(StepContext context, CallTool settings, List<Map<?, ?>> objects) {
        List<Long> rowIds = new ArrayList<>();
        int unclear = 0;
        for (Map<?, ?> object : objects) {
            ExtractedRow.Checked checked = ExtractedRow.check(object, null, settings.fields());
            String key = keyOf(object, settings.key());
            String reason = key == null ? "The tool gave no " + settings.key()
                    : checked.valid() ? null : "Not as the fields ask: " + String.join(", ", checked.wrong());
            String source = sourceName(settings) + "#" + (key == null ? rowIds.size() + 1 : key);
            rowIds.add(runs.saveRow(context.dataSource(), context.runId(), context.step().id(), source,
                    asJson(checked.values()), reason));
            unclear += reason == null ? 0 : 1;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.ROWS, rowIds.size());
        result.put(TaskResults.UNCLEAR, unclear);
        result.put(TaskResults.ROW_IDS, rowIds);
        return StepOutcome.of(result);
    }

    /**
     * Each object's file fetched with the file tool by its key - one file per answer (an MCP message holds about 6 MB of
     * files) - and read like a document of a store. A row without a key, a tool answering no file or an error, a file
     * too large for an answer is a document with its reason; the software out of reach fails the step, so {lastRun}
     * stays and the next run asks for the same again - so does what the next try may give: a link's server out of
     * reach, a scan without an image model or its key (a source keeps no read positions to read it again otherwise).
     */
    private StepOutcome documents(StepContext context, CallTool settings, List<Map<?, ?>> objects) {
        DocumentReader.Reading reading = reader.reading(context);
        Map<String, Object> fileSchema = schema(context, settings.fileTool(), settings);
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Map<?, ?> object : objects) {
            if (runs.cancelRequested(context.dataSource(), context.runId())) {
                throw ServiceException.conflict("task.cancelled", "The run was cancelled");
            }
            String key = keyOf(object, settings.key());
            String sourceName = sourceName(settings) + "#" + key;
            if (key == null) {
                documents.add(DocumentReader.Read.refused(sourceName, sourceName, "The tool gave no " + settings.key(),
                        false).document());
                continue;
            }
            Map<String, Object> arguments = ToolArguments.forSource(fileSchema, settings.fileWith());
            arguments.put(settings.key(), object.get(settings.key()));
            McpClient.CallResult file;
            try {
                file = connections.callForTask(context.tenant().id(), settings.connectionId(), settings.fileTool(),
                        arguments, true);
            } catch (ServiceException e) {
                if (!FILE_REFUSED.contains(e.code())) {
                    throw e;
                }
                documents.add(DocumentReader.Read.refused(key, sourceName, cut(e.getMessage()), false).document());
                continue;
            }
            if (file.linkFailedForNow()) {
                throw new ServiceException(HttpStatus.BAD_GATEWAY, "task.linkFailedForNow", "The file for " + key
                        + " could not be downloaded for now - " + cut(McpClient.unfetchedNotes(file)),
                        Map.of("name", key));
            }
            if (file.error() || file.files().isEmpty()) {
                String unfetched = McpClient.unfetchedNotes(file);
                documents.add(DocumentReader.Read.refused(key, sourceName, file.error() ? cut(file.text())
                        : "The tool " + settings.fileTool() + " gave no file for " + key
                        + (unfetched.isEmpty() ? "" : " " + cut(unfetched)), false).document());
                continue;
            }
            McpClient.ReturnedFile returned = file.files().getFirst();
            if (returned.content().length > DocumentService.MAX_DOCUMENT_BYTES) {
                documents.add(DocumentReader.Read.refused(returned.name(), sourceName, "The document is larger than "
                        + DocumentService.MAX_DOCUMENT_BYTES / 1024 / 1024 + " MB", false).document());
                continue;
            }
            DocumentReader.Read read = reading.read(returned.name(), returned.name(), sourceName, returned.content());
            if (read.again()) {
                throw ServiceException.conflict("task.documentNotYet", "The document " + returned.name()
                        + " cannot be read yet - " + read.document().get("problem"), Map.of("name", returned.name()));
            }
            documents.add(read.document());
        }
        List<Long> fileIds = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            if (document.get("fileId") instanceof Long id) {
                fileIds.add(id);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.FILES, documents.size());
        result.put(TaskResults.FILE_IDS, fileIds);
        result.put(TaskResults.DOCUMENTS, documents);
        return StepOutcome.of(result);
    }

    /** {lastRun:<format>} as the start of the task's last successful run in the installation's zone; none yet: "". */
    private Map<String, String> withLastRun(StepContext context, Map<String, String> with) {
        Instant last = runs.lastSuccessfulStart(context.dataSource(), context.taskId(), context.runId());
        Map<String, String> filled = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : with.entrySet()) {
            filled.put(entry.getKey(), TaskCompiler.LAST_RUN.matcher(entry.getValue()).replaceAll(match ->
                    Matcher.quoteReplacement(last == null ? ""
                            : DateTimeFormatter.ofPattern(match.group(1)).format(last.atZone(zone)))));
        }
        return filled;
    }

    /** The JSON array of objects the tool answered with - an array anywhere in its text, objects only. */
    private List<Map<?, ?>> objects(String tool, String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        List<?> list = null;
        if (start >= 0 && end > start) {
            try {
                Object parsed = json.readValue(text.substring(start, end + 1), Object.class);
                list = parsed instanceof List<?> items ? items : null;
            } catch (IOException | RuntimeException e) {
                list = null;
            }
        }
        if (list == null || list.stream().anyMatch(item -> !(item instanceof Map<?, ?>))) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "task.toolAnswerInvalid", "The tool " + tool
                    + " answered with no JSON array of objects", Map.of("tool", tool));
        }
        if (list.size() > MAX_SOURCE_ROWS) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "task.toolAnswerTooLarge", "The tool " + tool
                    + " listed " + list.size() + " - at most " + MAX_SOURCE_ROWS + " an answer; let it give the rest "
                    + "with the next run (since)", Map.of("tool", tool, "max", String.valueOf(MAX_SOURCE_ROWS)));
        }
        List<Map<?, ?>> objects = new ArrayList<>();
        list.forEach(item -> objects.add((Map<?, ?>) item));
        return objects;
    }

    private static @Nullable String keyOf(Map<?, ?> object, String key) {
        Object value = object.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    /** "<connection>/<tool>" - with the key, a row's source; the same row of the software gives the same. */
    private static String sourceName(CallTool settings) {
        return settings.connection() + "/" + settings.tool();
    }

    private String asJson(Map<String, Object> values) {
        try {
            return json.writeValueAsString(values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The tool's input schema as the connection read it last - its properties decide what goes into a call. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(StepContext context, String tool, CallTool settings) {
        String schema = connections.toolSchema(context.tenant().id(), settings.connectionId(), tool);
        try {
            return schema == null ? Map.of() : json.readValue(schema, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String cut(String text) {
        return text.length() <= MAX_ANSWER ? text : text.substring(0, MAX_ANSWER) + " [...]";
    }
}
