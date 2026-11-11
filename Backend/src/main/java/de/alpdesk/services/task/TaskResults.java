package de.alpdesk.services.task;

import de.alpdesk.services.task.TaskDefinition.CallTool;
import de.alpdesk.services.task.TaskDefinition.Step;
import de.alpdesk.services.task.TaskDefinition.StepType;

import java.util.List;

/**
 * The structured results the steps give, kept in the graph's state and in the run's steps - small maps of counts and
 * references, never the files' content. Conditions read only the counts.
 */
public final class TaskResults {

    public static final String FILES = "files";
    public static final String LINES = "lines";
    public static final String TRUNCATED = "truncated";
    public static final String FILE_IDS = "fileIds";
    public static final String POSITIONS = "positions";
    public static final String ERRORS = "errors";
    public static final String WARNINGS = "warnings";
    public static final String REPORTS = "reports";
    public static final String UNCLEAR = "unclear";
    public static final String OK = "ok";
    public static final String CHECKS = "checks";
    public static final String SENT = "sent";
    /** A prompt step's answer: its length, and the word picked from its choices. */
    public static final String CHARS = "chars";
    public static final String CHOICE = "choice";
    /** A read_documents step's documents: name, source, the run file's id - or why it could not be read. */
    public static final String DOCUMENTS = "documents";
    /** An extract step's rows - their ids in the tenant database, and how many. */
    public static final String ROWS = "rows";
    public static final String ROW_IDS = "rowIds";
    /** A write_file step's rows already in its files, and the files it wrote. */
    public static final String SKIPPED = "skipped";
    public static final String WRITTEN = "written";
    /** A call_tool step's calls that failed, and every call with its source and the tool's answer. */
    public static final String FAILED = "failed";
    public static final String CALLS = "calls";

    private TaskResults() {
    }

    /** What this step counts - the names a condition may use; a call_tool source counts what it gives. */
    public static List<String> counts(Step step) {
        CallTool call = step.callTool();
        if (call != null && call.source(step)) {
            return call.fields() != null ? List.of(ROWS, UNCLEAR) : List.of(FILES);
        }
        return counts(step.type());
    }

    private static List<String> counts(StepType type) {
        return switch (type) {
            case READ_FILES -> List.of(FILES, LINES);
            case READ_DOCUMENTS -> List.of(FILES);
            case EXTRACT -> List.of(ROWS, UNCLEAR);
            case WRITE_FILE -> List.of(ROWS, SKIPPED);
            case CALL_TOOL, CALL_AGENT -> List.of(SENT, FAILED);
            case AGENT, VERIFY -> List.of(ERRORS, WARNINGS, REPORTS, UNCLEAR, OK);
            case PROMPT -> List.of(CHARS);
            case SEND_MAIL -> List.of();
        };
    }
}
