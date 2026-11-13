package de.alpdesk.services.task;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A task as the engine runs it (docs/architecture/tasks.md "The format"): compiled from what its tenant admin wrote,
 * every name resolved to its id - stored as JSON next to the text, and kept with every run.
 *
 * @param description what it is for, said to whoever may start it from outside (A2A, MCP, /api/v1); null: none
 * @param notifyTo who hears of a failed run and of one that ended unclear (written "notify")
 * @param triggers when it starts by itself; none: only by hand, through the client software or MCP
 */
@Serdeable
public record TaskDefinition(String name, @Nullable String description, List<String> notifyTo, List<Trigger> triggers,
                             List<Step> steps) {

    public Step step(String id) {
        return steps.stream().filter(step -> step.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No step " + id));
    }

    public int indexOf(String id) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * One step; of the type's settings only its own are set (a verify step has agent settings too: its model, and the
     * check points of the step it verifies).
     *
     * @param when skipped when false
     * @param next checked from top to bottom after the step ran, the first true one wins; none true: the next step
     * @param verifies the agent step a verify step verifies (written "of")
     * @param choices   the words a prompt step's model picks one of (empty: none) - a condition reads the pick
     * @param line where the step begins in the text - for the graph and its errors
     * @param readDocuments a read_documents step's settings (a definition stored before it existed has none)
     * @param extract       an extract step's fields - its model and instructions are its agent
     * @param writeFile     a write_file step's store, file and CSV dialect
     * @param callTool      a call_tool step's connection, tool and arguments
     * @param callAgent     a call_agent step's A2A agent, skill and prompt (docs/architecture/tasks.md "call_agent")
     * @param assistant     a prompt step answered by the tenant's assistant: its areas, software, web search, template
     */
    @Serdeable
    public record Step(String id, StepType type, List<String> input, @Nullable Condition when, List<Edge> next,
                       @Nullable ReadFiles readFiles, @Nullable Agent agent, @Nullable String verifies,
                       @Nullable SendMail sendMail, List<String> choices, int line,
                       @Nullable ReadDocuments readDocuments, @Nullable Extract extract,
                       @Nullable WriteFile writeFile, @Nullable CallTool callTool, @Nullable CallAgent callAgent,
                       @Nullable AssistantAsk assistant) {

        /** A step of this type with none of the types' settings yet - each added with its with... method. */
        public static Step of(String id, StepType type, List<String> input, @Nullable Condition when, int line) {
            return new Step(id, type, input, when, List.of(), null, null, null, null, List.of(), line, null, null, null,
                    null, null, null);
        }

        public Step withNext(List<Edge> edges) {
            return new Step(id, type, input, when, edges, readFiles, agent, verifies, sendMail, choices, line,
                    readDocuments, extract, writeFile, callTool, callAgent, assistant);
        }

        public Step withReadFiles(ReadFiles settings) {
            return new Step(id, type, input, when, next, settings, agent, verifies, sendMail, choices, line,
                    readDocuments, extract, writeFile, callTool, callAgent, assistant);
        }

        public Step withAgent(@Nullable Agent settings) {
            return new Step(id, type, input, when, next, readFiles, settings, verifies, sendMail, choices, line,
                    readDocuments, extract, writeFile, callTool, callAgent, assistant);
        }

        public Step withVerifies(@Nullable String of) {
            return new Step(id, type, input, when, next, readFiles, agent, of, sendMail, choices, line, readDocuments,
                    extract, writeFile, callTool, callAgent, assistant);
        }

        public Step withSendMail(SendMail settings) {
            return new Step(id, type, input, when, next, readFiles, agent, verifies, settings, choices, line,
                    readDocuments, extract, writeFile, callTool, callAgent, assistant);
        }

        public Step withChoices(List<String> words) {
            return new Step(id, type, input, when, next, readFiles, agent, verifies, sendMail, words, line,
                    readDocuments, extract, writeFile, callTool, callAgent, assistant);
        }

        public Step withReadDocuments(ReadDocuments settings) {
            return new Step(id, type, input, when, next, readFiles, agent, verifies, sendMail, choices, line, settings,
                    extract, writeFile, callTool, callAgent, assistant);
        }

        public Step withExtract(Extract settings) {
            return new Step(id, type, input, when, next, readFiles, agent, verifies, sendMail, choices, line,
                    readDocuments, settings, writeFile, callTool, callAgent, assistant);
        }

        public Step withWriteFile(WriteFile settings) {
            return new Step(id, type, input, when, next, readFiles, agent, verifies, sendMail, choices, line,
                    readDocuments, extract, settings, callTool, callAgent, assistant);
        }

        public Step withCallTool(CallTool settings) {
            return new Step(id, type, input, when, next, readFiles, agent, verifies, sendMail, choices, line,
                    readDocuments, extract, writeFile, settings, callAgent, assistant);
        }

        public Step withCallAgent(CallAgent settings) {
            return new Step(id, type, input, when, next, readFiles, agent, verifies, sendMail, choices, line,
                    readDocuments, extract, writeFile, callTool, settings, assistant);
        }

        public Step withAssistant(@Nullable AssistantAsk settings) {
            return new Step(id, type, input, when, next, readFiles, agent, verifies, sendMail, choices, line,
                    readDocuments, extract, writeFile, callTool, callAgent, settings);
        }
    }

    /**
     * A schedule: every so many minutes (from the last scheduled start), or a cron expression (six fields, in the
     * installation's time zone) - one of the two.
     */
    @Serdeable
    public record Trigger(@Nullable Integer everyMinutes, @Nullable String cron, int line) {
    }

    /** An edge after a step; one back (to an earlier step or the step itself) is taken at most maxRounds times. */
    @Serdeable
    public record Edge(@Nullable Condition when, String to, @Nullable Integer maxRounds) {
    }

    /**
     * @param folder  relative to the store's folder as the task wrote it - placed below the store's folder only when a
     *                run reads, so a store moved to another folder takes its tasks along
     * @param pattern a file name with * and ? and date placeholders ({yyyy-MM-dd})
     * @param days    the dates the placeholders stand for: today and the days before
     */
    @Serdeable
    public record ReadFiles(Long storeId, String store, String folder, String pattern, int days, boolean onlyNew,
                            List<String> include, List<String> exclude, @Nullable FirstRun firstRun,
                            @Nullable List<Mask> mask, @Nullable Integer minLines) {

        /** A definition stored before firstRun existed reads from the start, as it always did. */
        public boolean startsAtEnd() {
            return firstRun == FirstRun.END;
        }

        /** A definition stored before mask existed masks nothing. */
        public List<Mask> masks() {
            return mask == null ? List.of() : mask;
        }
    }

    /**
     * A read_documents step: whole documents of a store's folder, each read once (docs/architecture/tasks.md
     * "read_documents").
     *
     * @param folder     relative to the store's folder, as for read_files
     * @param patterns   file names with * and ? - a file matching any of them is read; "*" every file
     * @param subfolders whether the folder's subfolders are read too
     */
    @Serdeable
    public record ReadDocuments(Long storeId, String store, String folder, List<String> patterns, boolean subfolders) {
    }

    /**
     * A write_file step: the rows of its input as a CSV in a store (docs/architecture/tasks.md "write_file").
     *
     * @param file       relative to the store's folder, with placeholders: {yyyy}, {MM}, {dd} the run's date,
     *                   {<date field>:yyyy} / {<date field>:MM} the row's, {<choice field>} the row's word
     * @param replace    the file written anew with this run's rows; false: the rows appended
     * @param dateFormat yMd only, e.g. dd.MM.yyyy
     */
    @Serdeable
    public record WriteFile(Long storeId, String store, String file, boolean replace, String separator, String decimal,
                            String dateFormat) {
    }

    /**
     * A call_tool step (docs/architecture/tasks.md "call_tool"): a tool of a connected software called with the rows of
     * its input - one call per row, or one with every row ("all") - or, without input, as a source of rows or documents.
     *
     * @param with     fixed arguments beside the row's fields, as written
     * @param fields   a source's columns: the properties of the objects its tool answers with; null for a target, or a
     *                 source whose tool gives documents
     * @param key      a source's field that makes a row unique - its source
     * @param fileTool a source giving documents: the tool called for each listed row's file
     * @param fileWith the file tool's fixed arguments besides the row's key
     */
    @Serdeable
    public record CallTool(Long connectionId, String connection, String tool, boolean all, Map<String, String> with,
                           @Nullable List<Field> fields, @Nullable String key, @Nullable String fileTool,
                           @Nullable Map<String, String> fileWith) {

        /** Without input: it gives rows or documents instead of sending them. */
        public boolean source(Step step) {
            return step.input().isEmpty();
        }
    }

    /**
     * A prompt step answered by the tenant's assistant (docs/architecture/tasks.md "The step prompt", assistant: true):
     * the company knowledge and the areas named, the reading tools of the software named (A2A agents too), the web
     * search when asked, a template's instructions. Ids and names, as the task's text named them.
     */
    @Serdeable
    public record AssistantAsk(List<Long> areaIds, List<String> areas, List<Long> connectionIds, List<String> software,
                               boolean web, @Nullable Long templateId, @Nullable String template) {
    }

    /**
     * A call_agent step (docs/architecture/tasks.md "call_agent"): the rows of its extract step sent to an A2A agent of
     * the tenant's connected software - a message per row, or one with every row ("all").
     *
     * @param prompt what the agent is to do with the rows, sent before them; null: "Row of <task>"
     * @param with   fixed values sent beside the row's fields, as written
     */
    @Serdeable
    public record CallAgent(Long connectionId, String agent, @Nullable String skill, boolean all,
                            @Nullable String prompt, Map<String, String> with) {
    }

    /**
     * An extract step: the table's columns every document gives a row of (docs/architecture/tasks.md "extract").
     *
     * @param knowledge whether the model may search the company knowledge (rules kept as documents)
     */
    @Serdeable
    public record Extract(List<Field> fields, boolean knowledge) {
    }

    /**
     * A column: its name, the type its value is checked against, whether a row needs it, what the model is told about
     * it, and - a choice - the words it may be.
     */
    @Serdeable
    public record Field(String name, FieldType type, boolean required, @Nullable String description,
                        List<String> values) {
    }

    /** What a field's value is: checked by the engine, written by it - never the model's text for a number or date. */
    public enum FieldType {
        TEXT, DATE, AMOUNT, PERCENT, NUMBER, CHOICE;

        /** As written in a task: text, date, ... */
        public String written() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What a read_files step replaces with *** before a line is kept: the value after a text up to the next separator,
     * or every match of a regular expression - exactly one of the two.
     */
    @Serdeable
    public record Mask(@Nullable String after, @Nullable String regex) {
    }

    /** Where a task's first run starts in the files it finds: their start, or their end - only what comes after. */
    public enum FirstRun {
        START, END
    }

    /** An agent's or verify step's model, its instructions and check points - and a prompt step's model and prompt. */
    @Serdeable
    public record Agent(Long modelId, String model, @Nullable String instructions, List<CheckPoint> checks) {
    }

    @Serdeable
    public record CheckPoint(String name, Severity severity, String instruction) {
    }

    @Serdeable
    public record SendMail(List<String> to, String subject) {
    }

    public enum StepType {
        READ_FILES, AGENT, VERIFY, PROMPT, SEND_MAIL, READ_DOCUMENTS, EXTRACT, WRITE_FILE, CALL_TOOL, CALL_AGENT;

        /** As written in a task: read_files, agent, ... */
        public String written() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What a finding of a check point counts as. A report is no problem found but something to hand on - a list, an
     * overview: its note goes into the mail whenever it has lines, and the verification leaves it as it is.
     */
    public enum Severity {
        ERROR, WARNING, REPORT
    }
}
