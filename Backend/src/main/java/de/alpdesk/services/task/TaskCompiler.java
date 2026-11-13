package de.alpdesk.services.task;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.entities.filestore.FileStore;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.mail.MailSender;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.filestore.FileStoreService;
import de.alpdesk.services.mcp.McpConnectionService;
import de.alpdesk.services.task.TaskDefinition.Agent;
import de.alpdesk.services.task.TaskDefinition.AssistantAsk;
import de.alpdesk.services.task.TaskDefinition.CallAgent;
import de.alpdesk.entities.assistant.ChatTemplate;
import de.alpdesk.entities.mcp.McpConnectionProtocol;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.document.KnowledgeAreaService;
import de.alpdesk.services.task.TaskDefinition.CallTool;
import de.alpdesk.services.task.TaskDefinition.CheckPoint;
import de.alpdesk.services.task.TaskDefinition.Edge;
import de.alpdesk.services.task.TaskDefinition.Extract;
import de.alpdesk.services.task.TaskDefinition.Field;
import de.alpdesk.services.task.TaskDefinition.FieldType;
import de.alpdesk.services.task.TaskDefinition.FirstRun;
import de.alpdesk.services.task.TaskDefinition.Mask;
import de.alpdesk.services.task.TaskDefinition.ReadDocuments;
import de.alpdesk.services.task.TaskDefinition.ReadFiles;
import de.alpdesk.services.task.TaskDefinition.SendMail;
import de.alpdesk.services.task.TaskDefinition.Severity;
import de.alpdesk.services.task.TaskDefinition.Step;
import de.alpdesk.services.task.TaskDefinition.StepType;
import de.alpdesk.services.task.TaskDefinition.Trigger;
import de.alpdesk.services.task.TaskDefinition.WriteFile;
import de.alpdesk.services.task.step.ReadFilesStep;
import de.alpdesk.services.task.step.ToolArguments;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.cron.CronExpression;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns a task's text into the definition the engine runs, or into its mistakes, each with its line
 * (docs/architecture/tasks.md "The check"): first the syntax (YAML 1.2 - JSON is YAML too), then the format, then the
 * tenant's references - the file stores, the models, the mail. Every mistake found is reported, not only the first.
 * The text is data: nothing in it is ever run as code.
 */
@Singleton
public class TaskCompiler {

    public static final int MAX_SOURCE = 64 * 1024;
    public static final int MAX_NAME = 80;
    static final int MAX_STEPS = 20;
    static final int MAX_CHECKS = 30;
    static final int MAX_TEXT = 4000;
    static final int MAX_SHORT_TEXT = 200;
    static final int MAX_RECIPIENTS = 20;
    static final int MAX_FILTERS = 50;
    static final int MAX_ROUNDS = 5;
    static final int MAX_DAYS = 31;

    private static final Pattern STEP_ID = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern DATE_LETTERS = Pattern.compile("[yMd._ -]+");
    private static final Set<String> TASK_FIELDS = Set.of("version", "name", "description", "notify", "triggers",
            "steps");
    private static final Set<String> TRIGGER_FIELDS = Set.of("type", "every", "cron");
    private static final Pattern EVERY = Pattern.compile("(\\d{1,5})\\s*([mhd])");
    static final int MAX_TRIGGERS = 10;
    private static final Set<String> COMMON_FIELDS = Set.of("id", "type", "input", "if", "next");
    private static final Map<StepType, Set<String>> TYPE_FIELDS = Map.of(
            StepType.READ_FILES, Set.of("store", "folder", "pattern", "days", "onlyNew", "firstRun", "include", "exclude",
                    "mask", "minLines"),
            StepType.AGENT, Set.of("model", "instructions", "checks"),
            StepType.VERIFY, Set.of("of", "model", "instructions"),
            StepType.PROMPT, Set.of("model", "prompt", "choices", "assistant", "areas", "software", "web", "template"),
            StepType.SEND_MAIL, Set.of("to", "subject"),
            StepType.READ_DOCUMENTS, Set.of("store", "folder", "pattern", "subfolders"),
            StepType.EXTRACT, Set.of("model", "instructions", "fields", "knowledge"),
            StepType.WRITE_FILE, Set.of("store", "file", "mode", "separator", "decimal", "dateFormat"),
            StepType.CALL_TOOL, Set.of("connection", "tool", "each", "with", "fields", "key", "file"),
            StepType.CALL_AGENT, Set.of("agent", "skill", "each", "prompt", "with"));
    private static final Pattern CHOICE_WORD = Pattern.compile("[a-z0-9_-]{1,40}");
    static final int MAX_CHOICES = 10;
    private static final Set<String> EDGE_FIELDS = Set.of("if", "to", "maxRounds");
    private static final Set<String> CHECK_FIELDS = Set.of("name", "severity", "instruction");
    private static final Set<String> MASK_FIELDS = Set.of("after", "regex");
    static final int MAX_MASKS = 20;
    static final int MAX_PATTERNS = 10;
    static final int MAX_FIELDS = 30;
    static final int MAX_FIELD_VALUES = 20;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_WITH = 20;
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    /** The columns the engine adds to every row - no field may take their name. */
    private static final Set<String> ROW_COLUMNS = Set.of("source", "unclear");
    private static final Set<String> FIELD_FIELDS = Set.of("name", "type", "required", "description", "values");
    private static final Set<String> FILE_TOOL_FIELDS = Set.of("tool", "with");
    /** {lastRun:<format>} in a source's with: the start of the task's last successful run. */
    public static final Pattern LAST_RUN = Pattern.compile("\\{lastRun:([^{}]+)}");
    /** {yyyy}, {MM}, {dd} the run's date; {<date field>:yyyy|MM|dd} the row's; {<choice field>} its word. */
    public static final Pattern FILE_PLACEHOLDER = Pattern.compile("\\{(yyyy|MM|dd)}|\\{([a-z][a-z0-9_]*)(?::(yyyy|MM|dd))?}");

    private final FileStoreService stores;
    private final TenantModelService models;
    private final ProviderKeyService keys;
    private final TenantRepository tenants;
    private final MailSender mail;
    private final McpConnectionService connections;
    private final KnowledgeAreaService areas;
    private final AssistantService assistants;
    private final JsonMapper json;
    private final ZoneId zone;

    public TaskCompiler(FileStoreService stores, TenantModelService models, ProviderKeyService keys,
                        TenantRepository tenants, MailSender mail, McpConnectionService connections,
                        KnowledgeAreaService areas, AssistantService assistants, JsonMapper json,
                        @Value("${app.timezone}") String zone) {
        this.areas = areas;
        this.assistants = assistants;
        this.zone = ZoneId.of(zone);
        this.stores = stores;
        this.models = models;
        this.keys = keys;
        this.tenants = tenants;
        this.mail = mail;
        this.connections = connections;
        this.json = json;
    }

    /** The definition, or null with the mistakes. */
    public record Compiled(@Nullable TaskDefinition definition, List<TaskError> errors) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public Compiled compile(Long tenantId, @Nullable String source) {
        Errors errors = new Errors();
        Node root = parse(source, errors);
        if (root == null) {
            return errors.result(null);
        }
        TaskDefinition draft = new Reader(errors, zone).task(root);
        if (!errors.isEmpty()) {
            return errors.result(null);
        }
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        return errors.result(new References(tenant, errors).resolve(draft));
    }

    /** Stage one: the syntax. Anchors and aliases are refused - a task stays plain to read, step by step. */
    private static @Nullable Node parse(@Nullable String source, Errors errors) {
        if (source == null || source.isBlank()) {
            errors.whole("task.empty", "The task is empty", Map.of());
            return null;
        }
        if (source.length() > MAX_SOURCE) {
            errors.whole("task.tooLarge", "The task may have at most " + MAX_SOURCE + " characters",
                    Map.of("max", String.valueOf(MAX_SOURCE)));
            return null;
        }
        LoadSettings settings = LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0)
                .setCodePointLimit(MAX_SOURCE * 4).build();
        try {
            Optional<Node> root = new Compose(settings).composeString(source);
            if (root.isEmpty()) {
                errors.whole("task.empty", "The task is empty", Map.of());
                return null;
            }
            if (refuseAnchors(root.get(), errors)) {
                return null;
            }
            return root.get();
        } catch (MarkedYamlEngineException e) {
            Mark mark = e.getProblemMark().or(e::getContextMark).orElse(null);
            String problem = e.getProblem() != null ? e.getProblem() : e.getMessage();
            errors.at(mark, "task.syntax", "Not valid YAML: " + problem, Map.of("problem", problem));
            return null;
        } catch (YamlEngineException e) {
            errors.whole("task.syntax", "Not valid YAML: " + e.getMessage(), Map.of("problem", e.getMessage()));
            return null;
        }
    }

    private static boolean refuseAnchors(Node node, Errors errors) {
        if (node.getAnchor().isPresent()) {
            errors.at(node, "task.anchor", "Anchors and aliases (&, *) are not used in a task", Map.of());
            return true;
        }
        boolean found = false;
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                found |= refuseAnchors(tuple.getKeyNode(), errors) || refuseAnchors(tuple.getValueNode(), errors);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                found |= refuseAnchors(item, errors);
            }
        }
        return found;
    }

    /** Stage two: the format - fields, types, ids, inputs, edges and conditions. */
    private static final class Reader {
        private final Errors errors;
        private final ZoneId zone;
        private final Map<String, StepType> typesSeen = new LinkedHashMap<>();

        Reader(Errors errors, ZoneId zone) {
            this.errors = errors;
            this.zone = zone;
        }

        @Nullable TaskDefinition task(Node root) {
            Fields task = fields(root, TASK_FIELDS);
            if (task == null) {
                return null;
            }
            Long version = task.number("version", null, 1, 1);
            if (version == null && task.has("version")) {
                return null;
            }
            if (!task.has("version")) {
                errors.at(root, "task.fieldRequired", "The field version is required", Map.of("field", "version"));
            }
            String name = task.text("name", true, MAX_NAME);
            String description = task.text("description", false, MAX_DESCRIPTION);
            List<String> notify = recipients(task, "notify", true);
            List<Trigger> triggers = triggers(task);
            List<Node> stepNodes = task.list("steps", true);
            if (stepNodes != null && stepNodes.isEmpty()) {
                errors.at(task.node("steps"), "task.stepsRequired", "A task has at least one step", Map.of());
            }
            if (stepNodes != null && stepNodes.size() > MAX_STEPS) {
                errors.at(task.node("steps"), "task.tooMany", "At most " + MAX_STEPS + " steps",
                        Map.of("field", "steps", "max", String.valueOf(MAX_STEPS)));
                return null;
            }
            List<Step> steps = new ArrayList<>();
            List<Node> nextNodes = new ArrayList<>();
            for (Node node : stepNodes == null ? List.<Node>of() : stepNodes) {
                Step step = step(node, steps);
                if (step != null) {
                    steps.add(step);
                    nextNodes.add(node);
                }
            }
            List<Step> withEdges = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                withEdges.add(withEdges(steps.get(i), i, steps, nextNodes.get(i)));
            }
            return new TaskDefinition(name, description, notify, triggers, withEdges);
        }

        /** Schedules: type schedule with every (15m to 7d) or cron (six fields) - never more often than 15 minutes. */
        private List<Trigger> triggers(Fields task) {
            List<Node> nodes = task.list("triggers", false);
            List<Trigger> triggers = new ArrayList<>();
            if (nodes == null) {
                return triggers;
            }
            if (nodes.size() > MAX_TRIGGERS) {
                errors.at(task.node("triggers"), "task.tooMany", "At most " + MAX_TRIGGERS + " triggers",
                        Map.of("field", "triggers", "max", String.valueOf(MAX_TRIGGERS)));
                return triggers;
            }
            for (Node node : nodes) {
                Fields trigger = fields(node, TRIGGER_FIELDS);
                if (trigger == null) {
                    continue;
                }
                String type = trigger.text("type", true, 40);
                if (type != null && !type.equals("schedule")) {
                    errors.at(trigger.node("type"), "task.triggerTypeUnknown", "Unknown trigger type " + type
                            + " - schedule", Map.of("type", type));
                    continue;
                }
                String every = trigger.text("every", false, 20);
                String cron = trigger.text("cron", false, 100);
                if ((every == null) == (cron == null)) {
                    errors.at(node, "task.scheduleInvalid", "A schedule has every or cron - one of the two", Map.of());
                    continue;
                }
                if (every != null) {
                    Integer minutes = minutes(every);
                    if (minutes == null || minutes < TaskSchedule.MIN_INTERVAL.toMinutes() || minutes > 7 * 24 * 60) {
                        errors.at(trigger.node("every"), "task.everyInvalid", "every is a time from 15m to 7d, like 30m, "
                                + "5h or 1d: " + every, Map.of("every", every));
                        continue;
                    }
                    triggers.add(new Trigger(minutes, null, line(node)));
                } else if (!validCron(cron, zone)) {
                    errors.at(trigger.node("cron"), "task.cronInvalid", "Not a cron expression of six fields "
                            + "(second minute hour day month weekday), or a time that never comes: " + cron,
                            Map.of("cron", cron));
                } else if (TaskSchedule.tooOften(cron, zone)) {
                    errors.at(trigger.node("cron"), "task.scheduleTooOften", "A task runs at most every 15 minutes: "
                            + cron, Map.of("cron", cron));
                } else {
                    triggers.add(new Trigger(null, cron, line(node)));
                }
            }
            return triggers;
        }

        private static @Nullable Integer minutes(String every) {
            Matcher match = EVERY.matcher(every.strip());
            if (!match.matches()) {
                return null;
            }
            int amount = Integer.parseInt(match.group(1));
            return switch (match.group(2)) {
                case "m" -> amount;
                case "h" -> amount * 60;
                default -> amount * 24 * 60;
            };
        }

        /** Six fields that parse and a time that comes: "0 0 0 30 2 *" parses, but Micronaut finds no time for it. */
        private static boolean validCron(String cron, ZoneId zone) {
            if (cron.strip().split("\\s+").length != 6) {
                return false;
            }
            try {
                CronExpression.create(cron.strip()).nextTimeAfter(ZonedDateTime.now(zone));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        private @Nullable Step step(Node node, List<Step> before) {
            Fields common = fields(node, null);
            if (common == null) {
                return null;
            }
            String id = common.text("id", true, 40);
            String typeText = common.text("type", true, 40);
            if (id != null && !STEP_ID.matcher(id).matches()) {
                errors.at(common.node("id"), "task.stepIdInvalid", "A step's id is lower case letters, digits and _, "
                        + "beginning with a letter: " + id, Map.of("id", id));
            } else if (id != null && typesSeen.containsKey(id)) {
                errors.at(common.node("id"), "task.stepIdTaken", "Two steps have the id " + id, Map.of("id", id));
            }
            StepType type = typeText == null ? null : stepType(typeText);
            if (typeText != null && type == null) {
                errors.at(common.node("type"), "task.stepTypeUnknown", "Unknown step type " + typeText + " - "
                        + "read_files, read_documents, extract, write_file, call_tool, call_agent, agent, verify, prompt or send_mail",
                        Map.of("type", typeText));
            }
            if (id == null || type == null) {
                return null;
            }
            Set<String> allowed = new HashSet<>(COMMON_FIELDS);
            allowed.addAll(TYPE_FIELDS.get(type));
            common.refuseUnknown(allowed);
            typesSeen.put(id, type);

            List<String> input = inputs(common, type, before);
            Condition when = condition(common, "if", before);
            int line = line(node);
            Step step = Step.of(id, type, input, when, line);
            return switch (type) {
                case READ_FILES -> step.withReadFiles(readFiles(common));
                case READ_DOCUMENTS -> step.withReadDocuments(readDocuments(common));
                case AGENT -> step.withAgent(agent(common));
                case VERIFY -> verify(common, id, input, when, before, line);
                case PROMPT -> {
                    AssistantAsk assistant = assistantAsk(common);
                    yield step.withAgent(prompt(common, "prompt", true, assistant == null))
                            .withChoices(choices(common)).withAssistant(assistant);
                }
                case SEND_MAIL -> step.withSendMail(sendMail(common));
                case EXTRACT -> step.withAgent(prompt(common, "instructions", false, true)).withExtract(extract(common));
                case WRITE_FILE -> step.withWriteFile(writeFile(common, input, before));
                case CALL_TOOL -> step.withCallTool(callTool(common, input));
                case CALL_AGENT -> step.withCallAgent(callAgent(common));
            };
        }

        private Step verify(Fields fields, String id, List<String> input, @Nullable Condition when, List<Step> before,
                            int line) {
            String of = fields.text("of", true, 40);
            Step verified = before.stream().filter(step -> step.id().equals(of)).findFirst().orElse(null);
            if (of != null && (verified == null || verified.type() != StepType.AGENT)) {
                errors.at(fields.node("of"), "task.verifyOfInvalid", "of names an agent step before this one: " + of,
                        Map.of("of", of));
            }
            String model = fields.text("model", false, MAX_NAME);
            String instructions = fields.text("instructions", false, MAX_TEXT);
            Agent agent = verified == null || verified.agent() == null ? null
                    : new Agent(null, model != null ? model : verified.agent().model(), instructions,
                    verified.agent().checks());
            List<String> files = input.isEmpty() && verified != null ? verified.input() : input;
            return Step.of(id, StepType.VERIFY, files, when, line).withAgent(agent).withVerifies(of);
        }

        /**
         * A call_tool step as an input: extract takes a source giving documents, write_file, call_tool and send_mail one
         * giving rows - never a target, which gives neither.
         */
        private static boolean givesWhatIsTaken(Step source, StepType taker) {
            CallTool call = source.callTool();
            if (call == null || !source.input().isEmpty()) {
                return false;
            }
            return taker == StepType.EXTRACT ? call.fileTool() != null : call.fields() != null;
        }

        private List<String> inputs(Fields fields, StepType type, List<Step> before) {
            List<String> input = fields.texts("input", false, MAX_STEPS, 40);
            if (input == null) {
                input = List.of();
            }
            Set<StepType> takes = switch (type) {
                case READ_FILES, READ_DOCUMENTS -> Set.of();
                case AGENT, VERIFY -> Set.of(StepType.READ_FILES, StepType.PROMPT);
                case EXTRACT -> Set.of(StepType.READ_DOCUMENTS, StepType.CALL_TOOL);
                case WRITE_FILE, CALL_TOOL -> Set.of(StepType.EXTRACT, StepType.CALL_TOOL);
                case CALL_AGENT -> Set.of(StepType.EXTRACT);
                case PROMPT -> Set.of(StepType.READ_FILES, StepType.PROMPT, StepType.AGENT, StepType.VERIFY);
                case SEND_MAIL -> Set.of(StepType.AGENT, StepType.VERIFY, StepType.PROMPT, StepType.EXTRACT,
                        StepType.WRITE_FILE, StepType.CALL_TOOL);
            };
            if ((type == StepType.READ_FILES || type == StepType.READ_DOCUMENTS) && fields.has("input")) {
                errors.at(fields.node("input"), "task.inputNotTaken", "A " + type.written() + " step takes no input",
                        Map.of());
            }
            if ((type == StepType.AGENT || type == StepType.SEND_MAIL || type == StepType.EXTRACT
                    || type == StepType.WRITE_FILE || type == StepType.CALL_AGENT) && input.isEmpty()
                    && !fields.has("input")) {
                errors.at(fields.whole(), "task.fieldRequired", "The field input is required", Map.of("field", "input"));
            }
            // input: [] would make a step that needs its input end without doing anything, and a call_tool a target
            // at the check but a source at run time
            if (type != StepType.READ_FILES && type != StepType.READ_DOCUMENTS
                    && fields.node("input") instanceof SequenceNode sequence && sequence.getValue().isEmpty()) {
                errors.at(fields.node("input"), "task.fieldRequired", "The field input names no step",
                        Map.of("field", "input"));
            }
            if ((type == StepType.EXTRACT || type == StepType.WRITE_FILE || type == StepType.CALL_TOOL
                    || type == StepType.CALL_AGENT) && input.size() > 1) {
                errors.at(fields.node("input"), "task.inputOne", "An " + type.written() + " step takes one step's "
                        + (type == StepType.EXTRACT ? "documents" : "rows"), Map.of());
            }
            for (String name : input) {
                Step earlier = before.stream().filter(step -> step.id().equals(name)).findFirst().orElse(null);
                if (earlier == null) {
                    errors.at(fields.node("input"), "task.inputUnknown", "input names a step before this one: " + name,
                            Map.of("input", name));
                } else if (!takes.contains(earlier.type())) {
                    String expected = String.join(" or ", takes.stream().map(StepType::written).sorted().toList());
                    errors.at(fields.node("input"), "task.inputWrongType", "The input " + name + " must be a "
                            + expected + " step", Map.of("input", name, "expected", expected));
                } else if (earlier.type() == StepType.CALL_TOOL && !givesWhatIsTaken(earlier, type)) {
                    errors.at(fields.node("input"), "task.inputWrongForm", "The call_tool step " + name + " gives "
                            + (type == StepType.EXTRACT ? "no documents (file)" : "no rows (fields)") + " to take",
                            Map.of("input", name));
                }
            }
            return input;
        }

        private ReadFiles readFiles(Fields fields) {
            String store = fields.text("store", true, MAX_NAME);
            String folder = fields.text("folder", false, 1000);
            if (folder != null && FileStoreService.folderBelow("/", folder) == null) {
                errors.at(fields.node("folder"), "task.folderInvalid", "The folder is a path below the store's folder, "
                        + "without .. or \\: " + folder, Map.of("folder", folder));
            }
            String pattern = fields.text("pattern", true, MAX_SHORT_TEXT);
            if (pattern != null) {
                checkPattern(fields.node("pattern"), pattern);
            }
            Long days = fields.number("days", 1L, 1, MAX_DAYS);
            Boolean onlyNew = fields.bool("onlyNew", true);
            FirstRun firstRun = firstRun(fields, onlyNew == null || onlyNew);
            List<String> include = fields.texts("include", false, MAX_FILTERS, MAX_SHORT_TEXT);
            List<String> exclude = fields.texts("exclude", false, MAX_FILTERS, MAX_SHORT_TEXT);
            Long minLines = fields.number("minLines", null, 1, ReadFilesStep.MAX_LINES);
            return new ReadFiles(null, store, folder == null ? "" : folder, pattern, days == null ? 1 : days.intValue(),
                    onlyNew == null || onlyNew, include == null ? List.of() : include,
                    exclude == null ? List.of() : exclude, firstRun, masks(fields),
                    minLines == null ? null : minLines.intValue());
        }

        /**
         * Whole documents: one pattern or a list of them (names only - no dates, a document is read once whatever its
         * name says), the subfolders on request.
         */
        private ReadDocuments readDocuments(Fields fields) {
            String store = fields.text("store", true, MAX_NAME);
            String folder = fields.text("folder", false, 1000);
            if (folder != null && FileStoreService.folderBelow("/", folder) == null) {
                errors.at(fields.node("folder"), "task.folderInvalid", "The folder is a path below the store's folder, "
                        + "without .. or \\: " + folder, Map.of("folder", folder));
            }
            List<String> patterns = fields.texts("pattern", true, MAX_PATTERNS, MAX_SHORT_TEXT);
            if (patterns != null && patterns.isEmpty()) {
                errors.at(fields.node("pattern"), "task.fieldRequired", "The field pattern is required",
                        Map.of("field", "pattern"));
            }
            for (String pattern : patterns == null ? List.<String>of() : patterns) {
                if (pattern.contains("/") || pattern.contains("\\") || pattern.contains("{") || pattern.contains("}")) {
                    errors.at(fields.node("pattern"), "task.documentPatternInvalid", "A pattern is a file name with * "
                            + "and ?, without folders or dates: " + pattern, Map.of("pattern", pattern));
                }
            }
            Boolean subfolders = fields.bool("subfolders", false);
            return new ReadDocuments(null, store, folder == null ? "" : folder, patterns == null ? List.of() : patterns,
                    subfolders != null && subfolders);
        }

        /** Each entry is after (a plain text) or regex (a regular expression that matches something), not both. */
        private List<Mask> masks(Fields fields) {
            List<Node> nodes = fields.list("mask", false);
            if (nodes == null) {
                return List.of();
            }
            if (nodes.size() > MAX_MASKS) {
                errors.at(fields.node("mask"), "task.tooMany", "At most " + MAX_MASKS + " masks",
                        Map.of("field", "mask", "max", String.valueOf(MAX_MASKS)));
            }
            List<Mask> masks = new ArrayList<>();
            for (Node node : nodes) {
                Fields entry = fields(node, MASK_FIELDS);
                if (entry == null) {
                    continue;
                }
                String after = entry.text("after", false, MAX_SHORT_TEXT);
                String regex = entry.text("regex", false, MAX_SHORT_TEXT);
                if ((after == null) == (regex == null) || (after != null && after.isBlank())) {
                    errors.at(node, "task.maskInvalid", "A mask has after or regex - one of the two", Map.of());
                } else if (regex != null && checkRegex(entry.node("regex"), regex)) {
                    masks.add(new Mask(null, regex));
                } else if (after != null) {
                    masks.add(new Mask(after, null));
                }
            }
            return masks;
        }

        /** It compiles, and it matches something - one matching nothing would put *** between every character. */
        private boolean checkRegex(Node node, String regex) {
            try {
                if (Pattern.compile(regex).matcher("").matches()) {
                    errors.at(node, "task.maskRegexEmpty", "The regular expression matches an empty text: " + regex,
                            Map.of("regex", regex));
                    return false;
                }
                return true;
            } catch (PatternSyntaxException e) {
                errors.at(node, "task.maskRegexInvalid", "The regular expression is not valid: " + e.getDescription(),
                        Map.of("regex", regex, "problem", e.getDescription()));
                return false;
            }
        }

        /** start or end; end only goes with onlyNew - without read positions there is no "after" to start from. */
        private FirstRun firstRun(Fields fields, boolean onlyNew) {
            String text = fields.text("firstRun", false, 20);
            if (text == null) {
                return FirstRun.START;
            }
            FirstRun firstRun = switch (text) {
                case "start" -> FirstRun.START;
                case "end" -> FirstRun.END;
                default -> null;
            };
            if (firstRun == null) {
                errors.at(fields.node("firstRun"), "task.firstRunUnknown", "firstRun is start or end: " + text,
                        Map.of("firstRun", text));
                return FirstRun.START;
            }
            if (firstRun == FirstRun.END && !onlyNew) {
                errors.at(fields.node("firstRun"), "task.firstRunWithoutOnlyNew", "firstRun: end needs onlyNew: true - "
                        + "a step that reads its files whole every time has no first run", Map.of());
            }
            return firstRun;
        }

        /** A file name: no folders, * and ? as wildcards, {...} a date as DateTimeFormatter writes it (yMd only). */
        private void checkPattern(Node node, String pattern) {
            boolean invalid = pattern.contains("/") || pattern.contains("\\");
            Matcher placeholder = PLACEHOLDER.matcher(pattern);
            while (placeholder.find()) {
                String format = placeholder.group(1);
                invalid |= !DATE_LETTERS.matcher(format).matches() || !validDateFormat(format);
            }
            invalid |= PLACEHOLDER.matcher(pattern).replaceAll("").matches(".*[{}].*");
            if (invalid) {
                errors.at(node, "task.patternInvalid", "The pattern is a file name with * and ? and dates like "
                        + "{yyyy-MM-dd}, without folders: " + pattern, Map.of("pattern", pattern));
            }
        }

        private static boolean validDateFormat(String format) {
            try {
                DateTimeFormatter.ofPattern(format);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private Agent agent(Fields fields) {
            String model = fields.text("model", true, MAX_NAME);
            String instructions = fields.text("instructions", false, MAX_TEXT);
            List<Node> checkNodes = fields.list("checks", true);
            List<CheckPoint> checks = new ArrayList<>();
            if (checkNodes != null && checkNodes.isEmpty()) {
                errors.at(fields.node("checks"), "task.checksRequired", "An agent step has at least one check point",
                        Map.of());
            }
            if (checkNodes != null && checkNodes.size() > MAX_CHECKS) {
                errors.at(fields.node("checks"), "task.tooMany", "At most " + MAX_CHECKS + " check points",
                        Map.of("field", "checks", "max", String.valueOf(MAX_CHECKS)));
            }
            Set<String> names = new HashSet<>();
            for (Node node : checkNodes == null ? List.<Node>of() : checkNodes) {
                Fields check = fields(node, CHECK_FIELDS);
                if (check == null) {
                    continue;
                }
                String name = check.text("name", true, MAX_NAME);
                String severity = check.text("severity", true, 20);
                String instruction = check.text("instruction", true, MAX_TEXT);
                Severity level = severity == null ? null : severity(severity);
                if (severity != null && level == null) {
                    errors.at(check.node("severity"), "task.severityUnknown", "The severity is error, warning or report: "
                            + severity, Map.of("severity", severity));
                }
                if (name != null && !names.add(name.toLowerCase(Locale.ROOT))) {
                    errors.at(check.node("name"), "task.checkNameTaken", "Two check points are called " + name,
                            Map.of("name", name));
                }
                if (name != null && level != null && instruction != null) {
                    checks.add(new CheckPoint(name, level, instruction));
                }
            }
            return new Agent(null, model, instructions, checks);
        }

        /**
         * A prompt's model and its text - or an extract step's model and instructions -, kept as an agent's
         * instructions, without check points. An assistant's prompt may leave the model out (withModel decides).
         */
        private Agent prompt(Fields fields, String textField, boolean textRequired, boolean modelRequired) {
            String model = fields.text("model", modelRequired, MAX_NAME);
            String text = fields.text(textField, textRequired, MAX_TEXT);
            return new Agent(null, model, text, List.of());
        }

        /** The columns: 1 to 30, each named once and not as the engine's own; a choice lists its words. */
        private Extract extract(Fields fields) {
            Boolean knowledge = fields.bool("knowledge", false);
            return new Extract(columns(fields), knowledge != null && knowledge);
        }

        /** The table's columns of an extract step or a call_tool source: 1 to 30 fields. */
        private List<Field> columns(Fields fields) {
            List<Node> nodes = fields.list("fields", true);
            if (nodes != null && nodes.isEmpty()) {
                errors.at(fields.node("fields"), "task.fieldsRequired", "An extract step has at least one field",
                        Map.of());
            }
            if (nodes != null && nodes.size() > MAX_FIELDS) {
                errors.at(fields.node("fields"), "task.tooMany", "At most " + MAX_FIELDS + " fields",
                        Map.of("field", "fields", "max", String.valueOf(MAX_FIELDS)));
            }
            List<Field> columns = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Node node : nodes == null ? List.<Node>of() : nodes) {
                Field field = field(node, names);
                if (field != null) {
                    columns.add(field);
                }
            }
            return columns;
        }

        private @Nullable Field field(Node node, Set<String> names) {
            Fields entry = fields(node, FIELD_FIELDS);
            if (entry == null) {
                return null;
            }
            String name = entry.text("name", true, 40);
            String typeText = entry.text("type", true, 20);
            Boolean required = entry.bool("required", false);
            String description = entry.text("description", false, MAX_DESCRIPTION);
            if (name != null && (!FIELD_NAME.matcher(name).matches() || ROW_COLUMNS.contains(name))) {
                errors.at(entry.node("name"), "task.fieldNameInvalid", "A field's name is lower case letters, digits "
                        + "and _, beginning with a letter - not source or unclear: " + name, Map.of("name", name));
            } else if (name != null && !names.add(name)) {
                errors.at(entry.node("name"), "task.fieldNameTaken", "Two fields are called " + name,
                        Map.of("name", name));
            }
            FieldType type = typeText == null ? null : fieldType(typeText);
            if (typeText != null && type == null) {
                errors.at(entry.node("type"), "task.fieldTypeUnknown", "Unknown field type " + typeText + " - text, "
                        + "date, amount, percent, number or choice", Map.of("type", typeText));
            }
            List<String> values = choiceValues(entry, type);
            if (name == null || type == null) {
                return null;
            }
            return new Field(name, type, required != null && required, description, values);
        }

        /**
         * A CSV in a store: a relative path ending in .csv, below the store's folder; its placeholders the run's date or
         * a field of the rows - a date field's year, month or day, a choice field's word, never a text or a number
         * from a document. The dialect: separator ; or ,, decimal , or . (not both ,), dates in yMd.
         */
        private WriteFile writeFile(Fields fields, List<String> input, List<Step> before) {
            String store = fields.text("store", true, MAX_NAME);
            String file = fields.text("file", true, 500);
            if (file != null) {
                checkFile(fields.node("file"), file, rowFields(input, before));
            }
            String mode = fields.text("mode", false, 20);
            if (mode != null && !mode.equals("append") && !mode.equals("replace")) {
                errors.at(fields.node("mode"), "task.modeUnknown", "mode is append or replace: " + mode,
                        Map.of("mode", mode));
            }
            String separator = fields.text("separator", false, 5);
            String decimal = fields.text("decimal", false, 5);
            if (separator != null && !separator.equals(";") && !separator.equals(",")) {
                errors.at(fields.node("separator"), "task.separatorInvalid", "separator is ; or ,", Map.of());
            }
            if (decimal != null && !decimal.equals(",") && !decimal.equals(".")) {
                errors.at(fields.node("decimal"), "task.decimalInvalid", "decimal is , or .", Map.of());
            }
            String chosenSeparator = separator == null ? ";" : separator;
            String chosenDecimal = decimal == null ? "," : decimal;
            if (chosenSeparator.equals(",") && chosenDecimal.equals(",")) {
                errors.at(fields.node("decimal"), "task.dialectInvalid", "With , as the separator the decimal "
                        + "separator is .", Map.of());
            }
            String dateFormat = fields.text("dateFormat", false, 40);
            if (dateFormat != null && (!DATE_LETTERS.matcher(dateFormat).matches() || !validDateFormat(dateFormat))) {
                errors.at(fields.node("dateFormat"), "task.dateFormatInvalid", "dateFormat writes a date with y, M and "
                        + "d, e.g. dd.MM.yyyy: " + dateFormat, Map.of("dateFormat", dateFormat));
            }
            return new WriteFile(null, store, file, "replace".equals(mode), chosenSeparator, chosenDecimal,
                    dateFormat == null ? "yyyy-MM-dd" : dateFormat);
        }

        /**
         * A tool of a connected software, by the connection's and the tool's names: called once per row ("row", the
         * default) or once with every row ("all"); "with" its fixed arguments - texts, as a tool's schema then takes them.
         */
        private CallTool callTool(Fields fields, List<String> input) {
            String connection = fields.text("connection", true, MAX_NAME);
            String tool = fields.text("tool", true, MAX_SHORT_TEXT);
            String each = fields.text("each", false, 10);
            if (each != null && !each.equals("row") && !each.equals("all")) {
                errors.at(fields.node("each"), "task.eachUnknown", "each is row or all: " + each, Map.of("each", each));
            }
            Map<String, String> with = with(fields, "with");
            if (!input.isEmpty() || fields.has("input")) {
                for (String sourceField : List.of("fields", "key", "file")) {
                    if (fields.has(sourceField)) {
                        errors.at(fields.node(sourceField), "task.fieldNotTaken", "A call_tool step with input sends "
                                + "rows - " + sourceField + " belongs to one without input", Map.of("field", sourceField));
                    }
                }
                return new CallTool(null, connection, tool, "all".equals(each), with, null, null, null, null);
            }
            return source(fields, connection, tool, with);
        }

        /**
         * assistant: true - the prompt answered by the tenant's assistant, with the areas, software, web search and
         * template named; null without it (then none of those fields is taken).
         */
        private @Nullable AssistantAsk assistantAsk(Fields fields) {
            Boolean assistant = fields.bool("assistant", false);
            if (assistant == null || !assistant) {
                for (String field : List.of("areas", "software", "web", "template")) {
                    if (fields.has(field)) {
                        errors.at(fields.node(field), "task.fieldNotTaken", field + " belongs to a prompt step with "
                                + "assistant: true", Map.of("field", field));
                    }
                }
                return null;
            }
            List<String> areas = fields.texts("areas", false, 20, MAX_NAME);
            List<String> software = fields.texts("software", false, 20, MAX_NAME);
            Boolean web = fields.bool("web", false);
            return new AssistantAsk(List.of(), areas == null ? List.of() : areas, List.of(),
                    software == null ? List.of() : software, web != null && web, null,
                    fields.text("template", false, MAX_NAME));
        }

        /**
         * An A2A agent of the connected software, by its name (docs/architecture/tasks.md "call_agent"): sent each row
         * ("row", the default) or every row at once ("all"), with a prompt saying what to do with them and fixed values.
         */
        private CallAgent callAgent(Fields fields) {
            String agent = fields.text("agent", true, MAX_NAME);
            String skill = fields.text("skill", false, MAX_SHORT_TEXT);
            String each = fields.text("each", false, 10);
            if (each != null && !each.equals("row") && !each.equals("all")) {
                errors.at(fields.node("each"), "task.eachUnknown", "each is row or all: " + each, Map.of("each", each));
            }
            String prompt = fields.text("prompt", false, 2000);
            return new CallAgent(null, agent, skill, "all".equals(each), prompt, with(fields, "with"));
        }

        /**
         * A call_tool without input is a source: its tool lists objects - rows with "fields" (the properties taken, typed
         * as an extract step's) and their "key", or documents with "file" (the tool giving each listed row's file by
         * its "key"). Exactly one of the two. Its with may hold {lastRun:<format>}.
         */
        private CallTool source(Fields fields, String connection, String tool, Map<String, String> with) {
            boolean rows = fields.has("fields");
            boolean documents = fields.has("file");
            if (rows == documents) {
                errors.at(fields.whole(), "task.sourceFormRequired", "A call_tool step without input gives rows "
                        + "(fields) or documents (file) - one of the two", Map.of());
            }
            if (fields.has("each")) {
                errors.at(fields.node("each"), "task.fieldNotTaken", "each belongs to a call_tool step with input",
                        Map.of("field", "each"));
            }
            String key = fields.text("key", true, 40);
            checkLastRun(fields, "with", with);
            List<Field> columns = rows ? columns(fields) : null;
            if (columns != null && key != null && columns.stream().noneMatch(field -> field.name().equals(key))) {
                errors.at(fields.node("key"), "task.keyUnknown", "key names one of the fields: " + key,
                        Map.of("key", key));
            }
            String fileTool = null;
            Map<String, String> fileWith = null;
            if (documents) {
                Fields file = fields.tuple("file").getValueNode() instanceof MappingNode
                        ? fields(fields.node("file"), FILE_TOOL_FIELDS) : null;
                if (file == null) {
                    errors.at(fields.node("file"), "task.wrongType", "file is a mapping with the tool and its with",
                            Map.of("field", "file", "expected", "mapping"));
                } else {
                    fileTool = file.text("tool", true, MAX_SHORT_TEXT);
                    fileWith = with(file, "with");
                }
            }
            return new CallTool(null, connection, tool, false, with, columns, key, fileTool, fileWith);
        }

        /** {lastRun:<format>} placeholders in with: a format DateTimeFormatter takes. */
        private void checkLastRun(Fields fields, String field, Map<String, String> with) {
            for (String value : with.values()) {
                Matcher placeholder = LAST_RUN.matcher(value);
                while (placeholder.find()) {
                    try {
                        DateTimeFormatter.ofPattern(placeholder.group(1));
                    } catch (IllegalArgumentException e) {
                        errors.at(fields.node(field), "task.lastRunInvalid", "Not a date format: "
                                + placeholder.group(1), Map.of("format", placeholder.group(1)));
                    }
                }
            }
        }

        /** A mapping of names to plain values, at most 20. */
        private Map<String, String> with(Fields fields, String field) {
            NodeTuple tuple = fields.tuple(field);
            if (tuple == null) {
                return Map.of();
            }
            if (!(tuple.getValueNode() instanceof MappingNode mapping)) {
                errors.at(tuple.getValueNode(), "task.wrongType", "The field with is a mapping of names to values",
                        Map.of("field", "with", "expected", "mapping"));
                return Map.of();
            }
            if (mapping.getValue().size() > MAX_WITH) {
                errors.at(mapping, "task.tooMany", "At most " + MAX_WITH + " in with",
                        Map.of("field", "with", "max", String.valueOf(MAX_WITH)));
            }
            Map<String, String> with = new LinkedHashMap<>();
            for (NodeTuple entry : mapping.getValue()) {
                if (entry.getKeyNode() instanceof ScalarNode key && entry.getValueNode() instanceof ScalarNode value
                        && value.getValue().length() <= MAX_TEXT) {
                    with.put(key.getValue(), value.getValue());
                } else {
                    errors.at(entry.getKeyNode(), "task.wrongType", "An entry of with is a name and a plain value",
                            Map.of("field", "with", "expected", "text"));
                }
            }
            return with;
        }

        /** The fields of the rows the step writes - those of its extract step or call_tool source, by name. */
        private static Map<String, Field> rowFields(List<String> input, List<Step> before) {
            Map<String, Field> fields = new LinkedHashMap<>();
            for (Step step : before) {
                if (input.contains(step.id()) && step.extract() != null) {
                    step.extract().fields().forEach(field -> fields.put(field.name(), field));
                } else if (input.contains(step.id()) && step.callTool() != null && step.callTool().fields() != null) {
                    step.callTool().fields().forEach(field -> fields.put(field.name(), field));
                }
            }
            return fields;
        }

        private void checkFile(Node node, String file, Map<String, Field> fields) {
            boolean invalid = file.startsWith("/") || file.contains("\\") || file.contains("//")
                    || !file.toLowerCase(Locale.ROOT).endsWith(".csv")
                    || List.of(file.split("/")).contains("..") || List.of(file.split("/")).contains(".");
            Matcher placeholder = FILE_PLACEHOLDER.matcher(file);
            while (placeholder.find()) {
                String name = placeholder.group(2);
                if (name == null) {
                    continue;
                }
                Field field = fields.get(name);
                boolean datePart = placeholder.group(3) != null;
                boolean fits = field != null && (datePart ? field.type() == FieldType.DATE
                        : field.type() == FieldType.CHOICE);
                if (!fits) {
                    errors.at(node, "task.filePlaceholderInvalid", "A placeholder of the file is {yyyy}, {MM}, {dd}, "
                            + "a date field's {<name>:yyyy} or a choice field's {<name>} - not " + placeholder.group(),
                            Map.of("placeholder", placeholder.group()));
                }
            }
            invalid |= FILE_PLACEHOLDER.matcher(file).replaceAll("").matches(".*[{}].*");
            if (invalid) {
                errors.at(node, "task.fileInvalid", "The file is a path below the store's folder ending in .csv, "
                        + "without .. - e.g. Export/{yyyy}/rows.csv: " + file, Map.of("file", file));
            }
        }

        /** A choice's words: 2 to 20 different ones of lower case letters, digits, _ and -; no other type has any. */
        private List<String> choiceValues(Fields entry, @Nullable FieldType type) {
            if (type != FieldType.CHOICE) {
                if (entry.has("values")) {
                    errors.at(entry.node("values"), "task.valuesNotTaken", "Only a choice field has values", Map.of());
                }
                return List.of();
            }
            List<String> values = entry.texts("values", true, MAX_FIELD_VALUES, 40);
            if (values == null) {
                return List.of();
            }
            boolean invalid = values.size() < 2 || new HashSet<>(values).size() != values.size()
                    || values.stream().anyMatch(value -> !CHOICE_WORD.matcher(value).matches());
            if (invalid) {
                errors.at(entry.node("values"), "task.valuesInvalid", "A choice's values are 2 to " + MAX_FIELD_VALUES
                        + " different words of lower case letters, digits, _ and -",
                        Map.of("max", String.valueOf(MAX_FIELD_VALUES)));
                return List.of();
            }
            return values;
        }

        private static @Nullable FieldType fieldType(String text) {
            for (FieldType type : FieldType.values()) {
                if (type.written().equals(text)) {
                    return type;
                }
            }
            return null;
        }

        /** 2 to 10 words of lower case letters, digits, _ and -, each once; left out: none. */
        private List<String> choices(Fields fields) {
            List<String> choices = fields.texts("choices", false, MAX_CHOICES, 40);
            if (choices == null) {
                return List.of();
            }
            boolean invalid = choices.size() < 2 || new HashSet<>(choices).size() != choices.size()
                    || choices.stream().anyMatch(choice -> !CHOICE_WORD.matcher(choice).matches());
            if (invalid) {
                errors.at(fields.node("choices"), "task.choicesInvalid", "choices are 2 to " + MAX_CHOICES + " different "
                        + "words of lower case letters, digits, _ and -", Map.of("max", String.valueOf(MAX_CHOICES)));
                return List.of();
            }
            return choices;
        }

        private SendMail sendMail(Fields fields) {
            List<String> to = recipients(fields, "to", false);
            String subject = fields.text("subject", false, MAX_SHORT_TEXT);
            return new SendMail(to, subject);
        }

        private List<String> recipients(Fields fields, String field, boolean required) {
            List<String> recipients = fields.texts(field, required, MAX_RECIPIENTS, 254);
            if (recipients == null) {
                return List.of();
            }
            if (recipients.isEmpty() && required) {
                errors.at(fields.node(field), "task.fieldRequired", "The field " + field + " is required",
                        Map.of("field", field));
            }
            for (String recipient : recipients) {
                if (!isAddress(recipient)) {
                    errors.at(fields.node(field), "task.emailInvalid", "Not an e-mail address: " + recipient,
                            Map.of("email", recipient));
                }
            }
            return recipients;
        }

        /** The edges after a step - read once every step is known, since an edge may go forward. */
        private Step withEdges(Step step, int index, List<Step> steps, Node node) {
            Fields fields = fields(node, null);
            List<Node> edgeNodes = fields == null ? null : fields.list("next", false);
            if (edgeNodes == null) {
                return step;
            }
            List<Edge> edges = new ArrayList<>();
            for (Node edgeNode : edgeNodes) {
                Fields edge = fields(edgeNode, EDGE_FIELDS);
                if (edge == null) {
                    continue;
                }
                String to = edge.text("to", true, 40);
                Condition when = condition(edge, "if", steps.subList(0, index + 1));
                Long maxRounds = edge.number("maxRounds", null, 1, MAX_ROUNDS);
                int target = to == null ? -1 : indexOf(steps, to);
                if (to != null && target < 0) {
                    errors.at(edge.node("to"), "task.targetUnknown", "to names a step of the task: " + to,
                            Map.of("to", to));
                } else if (to != null && target <= index && maxRounds == null && !edge.has("maxRounds")) {
                    errors.at(edge.node("to"), "task.maxRoundsRequired", "An edge back to " + to + " needs maxRounds "
                            + "(1 to " + MAX_ROUNDS + ") - a loop never runs unbounded", Map.of("to", to,
                            "max", String.valueOf(MAX_ROUNDS)));
                } else if (to != null && target > index && edge.has("maxRounds")) {
                    errors.at(edge.node("maxRounds"), "task.maxRoundsOnlyBack", "maxRounds belongs to an edge back",
                            Map.of());
                }
                if (to != null) {
                    edges.add(new Edge(when, to, maxRounds == null ? null : maxRounds.intValue()));
                }
            }
            return step.withNext(edges);
        }

        /** A condition on the counts of the steps given (those before, an edge's own step too). */
        private @Nullable Condition condition(Fields fields, String field, List<Step> visible) {
            String text = fields.text(field, false, MAX_SHORT_TEXT);
            if (text == null) {
                return null;
            }
            Condition condition = Condition.parse(text);
            if (condition == null) {
                errors.at(fields.node(field), "task.conditionInvalid", "Not a condition: " + text + " - like "
                        + "verify.errors > 0, joined with && and ||", Map.of("condition", text));
                return null;
            }
            for (Condition.Comparison comparison : condition.anyOf().stream().flatMap(List::stream).toList()) {
                Step step = visible.stream().filter(s -> s.id().equals(comparison.step())).findFirst().orElse(null);
                if (step == null) {
                    errors.at(fields.node(field), "task.conditionStepUnknown", "The condition names a step that has "
                            + "not run here: " + comparison.step(), Map.of("step", comparison.step()));
                } else if (comparison.word() != null) {
                    if (!step.choices().contains(comparison.word())) {
                        String choices = String.join(", ", step.choices());
                        errors.at(fields.node(field), "task.conditionChoiceUnknown", "The step " + step.id()
                                + (choices.isEmpty() ? " has no choices" : " chooses " + choices) + ", not "
                                + comparison.word(), Map.of("step", step.id(), "choice", comparison.word(),
                                "choices", choices));
                    }
                } else if (!TaskResults.counts(step).contains(comparison.count())) {
                    String counts = String.join(", ", TaskResults.counts(step));
                    errors.at(fields.node(field), "task.conditionCountUnknown", "The step " + step.id() + " counts "
                            + (counts.isEmpty() ? "nothing" : counts) + ", not " + comparison.count(),
                            Map.of("step", step.id(), "count", comparison.count(), "counts", counts));
                }
            }
            return condition;
        }

        private @Nullable Fields fields(Node node, @Nullable Set<String> allowed) {
            if (!(node instanceof MappingNode mapping)) {
                errors.at(node, "task.wrongType", "Expected fields (a mapping) here", Map.of("expected", "mapping"));
                return null;
            }
            Fields fields = new Fields(mapping, errors);
            if (allowed != null) {
                fields.refuseUnknown(allowed);
            }
            return fields;
        }

        private static int indexOf(List<Step> steps, String id) {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        private static @Nullable StepType stepType(String text) {
            for (StepType type : StepType.values()) {
                if (type.written().equals(text)) {
                    return type;
                }
            }
            return null;
        }

        private static @Nullable Severity severity(String text) {
            return switch (text) {
                case "error" -> Severity.ERROR;
                case "warning" -> Severity.WARNING;
                case "report" -> Severity.REPORT;
                default -> null;
            };
        }
    }

    /** Stage three: the tenant's references - its stores, its models, the installation's mail. */
    private final class References {
        private final Tenant tenant;
        private final Errors errors;

        References(Tenant tenant, Errors errors) {
            this.tenant = tenant;
            this.errors = errors;
        }

        @Nullable TaskDefinition resolve(TaskDefinition draft) {
            if (!mail.enabled()) {
                errors.whole("task.mailNotConfigured", "No mail server is configured (APP_MAIL_HOST) - a task could not "
                        + "report what it found", Map.of());
            }
            List<AiModel> usable = models.findUsable(tenant.id()).stream()
                    .filter(model -> model.active() && model.kind() == ModelKind.CHAT).toList();
            List<Step> steps = new ArrayList<>();
            for (Step step : draft.steps()) {
                steps.add(switch (step.type()) {
                    case READ_FILES -> withStore(step);
                    case READ_DOCUMENTS -> withDocumentStore(step);
                    case WRITE_FILE -> withWritableStore(step);
                    case CALL_TOOL -> withTool(step, draft);
                    case CALL_AGENT -> withAgentConnection(step);
                    case PROMPT -> withAssistantNames(withModel(step, usable));
                    case AGENT, VERIFY, EXTRACT -> withModel(step, usable);
                    case SEND_MAIL -> step;
                });
            }
            return errors.isEmpty() ? new TaskDefinition(draft.name(), draft.description(), draft.notifyTo(),
                    draft.triggers(), steps) : null;
        }

        private Step withStore(Step step) {
            ReadFiles read = step.readFiles();
            FileStore store = readableStore(step, read.store());
            if (store == null) {
                return step;
            }
            return step.withReadFiles(new ReadFiles(store.id(), store.name(), read.folder(), read.pattern(), read.days(),
                    read.onlyNew(), read.include(), read.exclude(), read.firstRun(), read.masks(), read.minLines()));
        }

        private Step withDocumentStore(Step step) {
            ReadDocuments read = step.readDocuments();
            FileStore store = readableStore(step, read.store());
            if (store == null) {
                return step;
            }
            return step.withReadDocuments(new ReadDocuments(store.id(), store.name(), read.folder(), read.patterns(),
                    read.subfolders()));
        }

        /**
         * The connection and the tool by their names - a tool turned on -, and the arguments against its input schema:
         * every required one given by a field, the row's source or with; the fields' types and with's values as the
         * schema wants them.
         */
        private Step withTool(Step step, TaskDefinition draft) {
            CallTool call = step.callTool();
            boolean source = step.input().isEmpty();
            var found = foundTool(step, call.connection(), call.tool(), source);
            if (found == null) {
                return step;
            }
            String problem = source
                    ? ToolArguments.problemOfSource(schema(found.tool().inputSchema()), call.with().keySet(), null)
                    : ToolArguments.problem(schema(found.tool().inputSchema()), inputFields(step, draft), call.with(),
                    call.all());
            if (problem == null && call.fileTool() != null) {
                var fileTool = foundTool(step, call.connection(), call.fileTool(), true);
                if (fileTool == null) {
                    return step;
                }
                problem = ToolArguments.problemOfSource(schema(fileTool.tool().inputSchema()), call.fileWith().keySet(),
                        call.key());
            }
            if (problem != null) {
                errors.atLine(step.line(), "task.toolArgumentsInvalid", "The tool " + call.tool() + " does not take "
                        + "the step's arguments: " + problem, Map.of("tool", call.tool(), "problem", problem));
                return step;
            }
            return step.withCallTool(new CallTool(found.connection().id(), found.connection().name(),
                    found.tool().name(), call.all(), call.with(), call.fields(), call.key(), call.fileTool(),
                    call.fileWith()));
        }

        /**
         * The agent by its connection's name - an A2A connection of a tenant the admin allowed A2A -, and its skill on the
         * card as last read. Naming it in the task is the approval: no group's level counts.
         */
        private Step withAgentConnection(Step step) {
            CallAgent call = step.callAgent();
            if (call.agent() == null) {
                return step;
            }
            var agent = connections.agentForTask(tenant.id(), call.agent()).orElse(null);
            if (agent == null) {
                errors.atLine(step.line(), "task.agentUnknown", "The tenant has no A2A agent " + call.agent(),
                        Map.of("agent", call.agent()));
                return step;
            }
            if (!tenant.a2aAllowed()) {
                errors.atLine(step.line(), "task.a2aNotAllowed", "The tenant may not use A2A agents - the admin "
                        + "allows it", Map.of());
                return step;
            }
            if (call.skill() != null && !connections.skillsOf(agent).contains(call.skill())) {
                errors.atLine(step.line(), "task.skillUnknown", "The agent " + agent.name() + " has no skill "
                        + call.skill(), Map.of("agent", agent.name(), "skill", call.skill()));
                return step;
            }
            return step.withCallAgent(new CallAgent(agent.id(), agent.name(), call.skill(), call.all(), call.prompt(),
                    call.with()));
        }

        /**
         * An assistant prompt's areas, software and template by their names - each must exist; an A2A agent only for a
         * tenant the admin allowed A2A. Naming them in the task is the approval.
         */
        private Step withAssistantNames(Step step) {
            AssistantAsk ask = step.assistant();
            if (ask == null) {
                return step;
            }
            Map<String, Long> areaIds = new HashMap<>();
            areas.namesById(tenant.id()).forEach((id, name) -> areaIds.put(name.toLowerCase(Locale.ROOT), id));
            List<Long> chosenAreas = new ArrayList<>();
            for (String area : ask.areas()) {
                Long id = areaIds.get(area.strip().toLowerCase(Locale.ROOT));
                if (id == null) {
                    errors.atLine(step.line(), "task.areaUnknown", "The tenant has no knowledge area " + area,
                            Map.of("area", area));
                } else {
                    chosenAreas.add(id);
                }
            }
            List<Long> chosenSoftware = new ArrayList<>();
            for (String name : ask.software()) {
                var connection = connections.connectionForTask(tenant.id(), name).orElse(null);
                if (connection == null) {
                    errors.atLine(step.line(), "task.softwareUnknown", "The tenant has no connected software " + name,
                            Map.of("software", name));
                } else if (connection.protocol() == McpConnectionProtocol.A2A && !tenant.a2aAllowed()) {
                    errors.atLine(step.line(), "task.a2aNotAllowed", "The tenant may not use A2A agents - the admin "
                            + "allows it", Map.of());
                } else {
                    chosenSoftware.add(connection.id());
                }
            }
            Long templateId = null;
            if (ask.template() != null) {
                templateId = assistants.templates(tenant.id()).stream()
                        .filter(template -> template.name().equalsIgnoreCase(ask.template().strip()))
                        .map(ChatTemplate::id).findFirst().orElse(null);
                if (templateId == null) {
                    errors.atLine(step.line(), "task.templateUnknown", "The tenant has no chat template "
                            + ask.template(), Map.of("template", ask.template()));
                }
            }
            return step.withAssistant(new AssistantAsk(chosenAreas, ask.areas(), chosenSoftware, ask.software(),
                    ask.web(), templateId, ask.template()));
        }

        /** The tool turned on - a source's only one that reads (it is called without a person, on every run). */
        private McpConnectionService.@Nullable TaskTool foundTool(Step step, String connection, String tool,
                                                                  boolean reading) {
            var found = connections.toolForTask(tenant.id(), connection, tool).orElse(null);
            if (found == null) {
                errors.atLine(step.line(), "task.toolUnknown", "The connected software " + connection
                        + " offers no tool " + tool + " that is turned on", Map.of("connection", connection,
                        "tool", tool));
                return null;
            }
            if (reading && found.tool().changes()) {
                errors.atLine(step.line(), "task.toolChanges", "The tool " + tool + " changes something - a call_tool "
                        + "step without input calls only reading tools", Map.of("tool", tool));
                return null;
            }
            return found;
        }

        private static List<Field> inputFields(Step step, TaskDefinition draft) {
            Step input = draft.step(step.input().getFirst());
            if (input.extract() != null) {
                return input.extract().fields();
            }
            return input.callTool() != null && input.callTool().fields() != null ? input.callTool().fields() : List.of();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> schema(String schema) {
            try {
                return json.readValue(schema, Map.class);
            } catch (IOException | RuntimeException e) {
                return Map.of();
            }
        }

        /** A store tasks may write to: its switch on, and ready to be read - a write logs in the same way. */
        private Step withWritableStore(Step step) {
            WriteFile write = step.writeFile();
            FileStore store = readableStore(step, write.store());
            if (store == null) {
                return step;
            }
            if (!stores.writable(store)) {
                errors.atLine(step.line(), "task.storeNotWritable", "Tasks may not write into the file store "
                        + store.name() + " - a tenant admin allows it at the store", Map.of("store", store.name()));
                return step;
            }
            return step.withWriteFile(new WriteFile(store.id(), store.name(), write.file(), write.replace(),
                    write.separator(), write.decimal(), write.dateFormat()));
        }

        /** The tenant's store of this name, ready to be read - else null with the mistake at the step. */
        private @Nullable FileStore readableStore(Step step, String name) {
            FileStore store = stores.findByName(tenant.id(), name).orElse(null);
            if (store == null) {
                errors.atLine(step.line(), "task.storeUnknown", "The tenant has no file store " + name,
                        Map.of("store", name));
                return null;
            }
            if (!stores.readable(store)) {
                errors.atLine(step.line(), "task.storeNotReady", "The file store " + store.name() + " is not ready "
                        + "- check it on the page File stores first", Map.of("store", store.name()));
                return null;
            }
            return store;
        }

        private Step withModel(Step step, List<AiModel> usable) {
            Agent agent = step.agent();
            if (agent == null) {
                return step;
            }
            if (agent.model() == null) {
                // Only an assistant's prompt gets here: the tenant's agent answers it, no model of ours is asked.
                if (connections.answeringAgent(tenant.id()).isEmpty()) {
                    errors.atLine(step.line(), "task.modelRequiredWithoutAgent", "A model is needed - only a tenant "
                            + "answering through its A2A agent leaves it out", Map.of());
                }
                return step;
            }
            AiModel model = usable.stream()
                    .filter(candidate -> candidate.displayName().equalsIgnoreCase(agent.model().strip())
                            || candidate.modelName().equalsIgnoreCase(agent.model().strip()))
                    .findFirst().orElse(null);
            if (model == null) {
                errors.atLine(step.line(), "task.modelUnknown", "The tenant may use no chat model " + agent.model(),
                        Map.of("model", agent.model()));
                return step;
            }
            if (keys.find(tenant, model).isEmpty()) {
                errors.atLine(step.line(), "task.modelNoKey", "There is no API key for " + model.displayName(),
                        Map.of("model", model.displayName()));
                return step;
            }
            return step.withAgent(new Agent(model.id(), model.displayName(), agent.instructions(), agent.checks()));
        }
    }

    static boolean isAddress(String recipient) {
        try {
            new InternetAddress(recipient, true);
            return recipient.contains("@");
        } catch (AddressException e) {
            return false;
        }
    }

    private static int line(Node node) {
        return node.getStartMark().map(mark -> mark.getLine() + 1).orElse(0);
    }

    /** The fields of one mapping, read by name - each mistake recorded where it is. */
    private static final class Fields {
        private final MappingNode mapping;
        private final Errors errors;
        private final Map<String, NodeTuple> byName = new LinkedHashMap<>();

        Fields(MappingNode mapping, Errors errors) {
            this.mapping = mapping;
            this.errors = errors;
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key) {
                    // Composing alone does not apply setAllowDuplicateKeys - the last one would win silently
                    if (byName.putIfAbsent(key.getValue(), tuple) != null) {
                        errors.at(key, "task.duplicateField", "The field " + key.getValue() + " is there twice",
                                Map.of("field", key.getValue()));
                    }
                } else {
                    errors.at(tuple.getKeyNode(), "task.wrongType", "A field's name is a text",
                            Map.of("expected", "text"));
                }
            }
        }

        boolean has(String name) {
            return byName.containsKey(name);
        }

        @Nullable NodeTuple tuple(String name) {
            return byName.get(name);
        }

        Node whole() {
            return mapping;
        }

        /** The field's value, else the mapping itself - a mistake about a missing field stands at its mapping. */
        Node node(String name) {
            NodeTuple tuple = byName.get(name);
            return tuple == null ? mapping : tuple.getValueNode();
        }

        void refuseUnknown(Set<String> allowed) {
            for (Map.Entry<String, NodeTuple> field : byName.entrySet()) {
                if (!allowed.contains(field.getKey())) {
                    errors.at(field.getValue().getKeyNode(), "task.unknownField", "Unknown field " + field.getKey(),
                            Map.of("field", field.getKey()));
                }
            }
        }

        @Nullable String text(String name, boolean required, int max) {
            NodeTuple tuple = byName.get(name);
            if (tuple == null) {
                if (required) {
                    errors.at(mapping, "task.fieldRequired", "The field " + name + " is required", Map.of("field", name));
                }
                return null;
            }
            if (!(tuple.getValueNode() instanceof ScalarNode scalar)) {
                errors.at(tuple.getValueNode(), "task.wrongType", "The field " + name + " is a text",
                        Map.of("field", name, "expected", "text"));
                return null;
            }
            String value = scalar.getValue().strip();
            if (value.isEmpty()) {
                if (required) {
                    errors.at(scalar, "task.fieldRequired", "The field " + name + " is required", Map.of("field", name));
                }
                return null;
            }
            if (value.length() > max) {
                errors.at(scalar, "task.textTooLong", "The field " + name + " may have at most " + max + " characters",
                        Map.of("field", name, "max", String.valueOf(max)));
                return null;
            }
            return value;
        }

        @Nullable Long number(String name, @Nullable Long fallback, long min, long max) {
            NodeTuple tuple = byName.get(name);
            if (tuple == null) {
                return fallback;
            }
            Long value = tuple.getValueNode() instanceof ScalarNode scalar ? parseLong(scalar.getValue()) : null;
            if (value == null || value < min || value > max) {
                errors.at(tuple.getValueNode(), "task.numberInvalid", "The field " + name + " is a whole number from "
                        + min + " to " + max, Map.of("field", name, "min", String.valueOf(min), "max", String.valueOf(max)));
                return null;
            }
            return value;
        }

        /** true or false only - YAML 1.1's yes, no and on are texts. */
        @Nullable Boolean bool(String name, boolean fallback) {
            NodeTuple tuple = byName.get(name);
            if (tuple == null) {
                return fallback;
            }
            String value = tuple.getValueNode() instanceof ScalarNode scalar ? scalar.getValue() : "";
            return switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> {
                    errors.at(tuple.getValueNode(), "task.wrongType", "The field " + name + " is true or false",
                            Map.of("field", name, "expected", "boolean"));
                    yield null;
                }
            };
        }

        @Nullable List<Node> list(String name, boolean required) {
            NodeTuple tuple = byName.get(name);
            if (tuple == null) {
                if (required) {
                    errors.at(mapping, "task.fieldRequired", "The field " + name + " is required", Map.of("field", name));
                }
                return null;
            }
            if (!(tuple.getValueNode() instanceof SequenceNode sequence)) {
                errors.at(tuple.getValueNode(), "task.wrongType", "The field " + name + " is a list",
                        Map.of("field", name, "expected", "list"));
                return null;
            }
            return sequence.getValue();
        }

        /** A list of texts - a single text counts as a list of one. */
        @Nullable List<String> texts(String name, boolean required, int maxItems, int maxEach) {
            NodeTuple tuple = byName.get(name);
            if (tuple == null) {
                if (required) {
                    errors.at(mapping, "task.fieldRequired", "The field " + name + " is required", Map.of("field", name));
                }
                return null;
            }
            List<Node> items = tuple.getValueNode() instanceof SequenceNode sequence ? sequence.getValue()
                    : List.of(tuple.getValueNode());
            if (items.size() > maxItems) {
                errors.at(tuple.getValueNode(), "task.tooMany", "At most " + maxItems + " in " + name,
                        Map.of("field", name, "max", String.valueOf(maxItems)));
                return null;
            }
            List<String> values = new ArrayList<>();
            for (Node item : items) {
                if (!(item instanceof ScalarNode scalar) || scalar.getValue().isBlank()) {
                    errors.at(item, "task.wrongType", "The field " + name + " lists texts",
                            Map.of("field", name, "expected", "texts"));
                    continue;
                }
                if (scalar.getValue().strip().length() > maxEach) {
                    errors.at(item, "task.textTooLong", "An entry of " + name + " may have at most " + maxEach
                            + " characters", Map.of("field", name, "max", String.valueOf(maxEach)));
                    continue;
                }
                values.add(scalar.getValue().strip());
            }
            return values;
        }

        private static @Nullable Long parseLong(String text) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** The mistakes found, in the order of the text. */
    private static final class Errors {
        private final List<TaskError> found = new ArrayList<>();

        boolean isEmpty() {
            return found.isEmpty();
        }

        void at(Node node, String code, String message, Map<String, String> params) {
            at(node.getStartMark().orElse(null), code, message, params);
        }

        void at(@Nullable Mark mark, String code, String message, Map<String, String> params) {
            found.add(new TaskError(mark == null ? 0 : mark.getLine() + 1, mark == null ? 0 : mark.getColumn() + 1,
                    code, params, message));
        }

        void atLine(int line, String code, String message, Map<String, String> params) {
            found.add(new TaskError(line, 0, code, params, message));
        }

        void whole(String code, String message, Map<String, String> params) {
            found.add(new TaskError(0, 0, code, params, message));
        }

        Compiled result(@Nullable TaskDefinition definition) {
            List<TaskError> sorted = new ArrayList<>(found);
            sorted.sort((a, b) -> a.line() != b.line() ? Integer.compare(a.line(), b.line())
                    : Integer.compare(a.column(), b.column()));
            return new Compiled(sorted.isEmpty() ? definition : null, sorted);
        }
    }
}
