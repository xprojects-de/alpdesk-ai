package de.alpdesk.services.task.step;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.entities.task.RunFile;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.agent.AgentLoop;
import de.alpdesk.services.agent.AgentRun;
import de.alpdesk.services.ai.ChatOptions;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.KnowledgeScope;
import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.StepOutcome;
import de.alpdesk.services.task.TaskDefinition.Extract;
import de.alpdesk.services.task.TaskDefinition.Step;
import de.alpdesk.services.task.TaskResults;
import de.alpdesk.services.tools.ReadDocumentTool;
import de.alpdesk.services.tools.ReadFileLinesTool;
import de.alpdesk.services.tools.SearchFileTool;
import de.alpdesk.services.tools.SimilaritySearchTool;
import de.alpdesk.services.tools.ToolContext;
import de.alpdesk.services.tools.Toolbox;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * extract (docs/architecture/tasks.md "extract"): one row per document of its read_documents step - the tenant's chat
 * model takes the step's fields out of one document at a time, through the chat's AgentLoop (key, usage, cost), and the
 * engine checks every value by its field's type. An answer that fails gets one more round with the reason; failing
 * again, the row keeps what passed and is unclear. A document that could not be read is an unclear row with its reason,
 * no model asked. The rows are stored in the tenant database, the state keeps their ids. Not in a transaction: a
 * provider can take minutes per document.
 */
@Singleton
public class ExtractStep {

    private static final Set<String> TOOLS = Set.of(SearchFileTool.NAME, ReadFileLinesTool.NAME);
    private static final Set<String> KNOWLEDGE_TOOLS = Set.of(SimilaritySearchTool.NAME, ReadDocumentTool.NAME);

    private final TenantModelService models;
    private final ProviderKeyService keys;
    private final AgentLoop loop;
    private final Toolbox toolbox;
    private final TaskRunRepository runs;
    private final AssistantService assistants;
    private final JsonMapper json;
    private final int rounds;
    private final ZoneId zone;

    public ExtractStep(TenantModelService models, ProviderKeyService keys, AgentLoop loop, Toolbox toolbox,
                       TaskRunRepository runs, AssistantService assistants, JsonMapper json,
                       @Value("${app.task.agent-rounds}") int rounds,
                       @Value("${app.timezone}") String zone) {
        this.models = models;
        this.keys = keys;
        this.loop = loop;
        this.toolbox = toolbox;
        this.runs = runs;
        this.assistants = assistants;
        this.json = json;
        this.rounds = rounds;
        this.zone = ZoneId.of(zone);
    }

    public StepOutcome run(StepContext context) {
        List<Map<String, Object>> documents = documents(context);
        Asking asking = null;
        List<Long> rowIds = new ArrayList<>();
        int unclear = 0;
        for (Map<String, Object> document : documents) {
            if (runs.cancelRequested(context.dataSource(), context.runId())) {
                throw ServiceException.conflict("task.cancelled", "The run was cancelled");
            }
            String source = String.valueOf(document.get("source"));
            Row row;
            if (document.get("problem") instanceof String problem) {
                row = new Row(Map.of(), problem);
            } else {
                asking = asking != null ? asking : new Asking(context);
                row = asking.row(text(context, ((Number) document.get("fileId")).longValue()));
            }
            rowIds.add(runs.saveRow(context.dataSource(), context.runId(), context.step().id(), source,
                    asJson(row.values()), row.unclear()));
            unclear += row.unclear() == null ? 0 : 1;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.ROWS, rowIds.size());
        result.put(TaskResults.UNCLEAR, unclear);
        result.put(TaskResults.ROW_IDS, rowIds);
        return StepOutcome.of(result);
    }

    /** The documents of the input step, as it gave them - none when it was skipped or read nothing. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents(StepContext context) {
        Map<String, Object> result = context.results().get(context.step().input().getFirst());
        if (result == null || !(result.get(TaskResults.DOCUMENTS) instanceof List<?> documents)) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object document : documents) {
            list.add((Map<String, Object>) document);
        }
        return list;
    }

    /** One document's text at a time: all of a run's documents together can be more than the memory holds. */
    private RunFile text(StepContext context, long fileId) {
        return runs.findFiles(context.dataSource(), context.runId(), List.of(fileId)).getFirst();
    }

    /** A row: the values that passed, and why it is unclear (null: clear). */
    record Row(Map<String, Object> values, @Nullable String unclear) {
    }

    /** The model and its key, resolved once per step - only when a document is to be asked about. */
    private final class Asking {
        private final StepContext context;
        private final AiModel model;
        private final ResolvedKey key;

        Asking(StepContext context) {
            this.context = context;
            this.model = models.requireUsable(context.tenant().id(), context.step().agent().modelId(), ModelKind.CHAT);
            this.key = keys.resolve(context.tenant(), model);
        }

        Row row(RunFile document) {
            Step step = context.step();
            Extract extract = step.extract();
            // the company knowledge alone: a task has no user whose areas would count; as many hits as the tenant's chat
            int searchResults = extract.knowledge()
                    ? assistants.forQuestion(context.tenant().id(), null).searchResults() : 0;
            ToolContext tools = new ToolContext(context.tenant(), ToolContext.Asker.TASK, model, null,
                    KnowledgeScope.companyKnowledgeAnd(List.of()), context.dataSource(), null, false, searchResults,
                    ToolContext.Files.NONE);
            Set<String> offered = new HashSet<>(TOOLS);
            if (extract.knowledge()) {
                offered.addAll(KNOWLEDGE_TOOLS);
            }
            tools.onlyTools(offered);
            tools.stopWhen(() -> runs.cancelRequested(context.dataSource(), context.runId()));
            tools.countInto(context.spent());
            String question = question(context, document) + StepInputs.filesText(
                    List.of(new StepInputs.Input(step.input().getFirst(), List.of(document), false)), tools,
                    model.callsTools());
            List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from(TaskPrompts.EXTRACT_SYSTEM),
                    UserMessage.from(question)));
            List<ToolSpecification> specifications = toolbox.specificationsFor(tools);
            String answer = loop.run(new AgentRun(context.tenant(), null, model, key, ChatOptions.NONE, messages,
                    specifications, tools, rounds));
            String problem;
            ExtractedRow.Checked first = null;
            try {
                first = ExtractedRow.read(answer, extract.fields(), json);
                if (first.valid()) {
                    return new Row(first.values(), first.unclear());
                }
                problem = "these fields are not as asked: " + String.join(", ", first.wrong());
            } catch (ExtractedRow.Invalid invalid) {
                problem = invalid.getMessage();
            }
            messages.add(AiMessage.from(answer));
            messages.add(UserMessage.from(TaskPrompts.extractAgain(problem)));
            String again = loop.run(new AgentRun(context.tenant(), null, model, key, ChatOptions.NONE, messages,
                    specifications, tools, 2));
            try {
                ExtractedRow.Checked second = ExtractedRow.read(again, extract.fields(), json);
                if (second.valid()) {
                    return new Row(second.values(), second.unclear());
                }
                return new Row(second.values(), joined(second.unclear(),
                        "Not taken from the document: " + String.join(", ", second.wrong())));
            } catch (ExtractedRow.Invalid invalid) {
                // the first answer's values that passed are better than none
                return first == null ? new Row(Map.of(), model.displayName() + " gave no valid result: "
                        + invalid.getMessage()) : new Row(first.values(), joined(first.unclear(),
                        "Not taken from the document: " + String.join(", ", first.wrong())));
            }
        }

        private String question(StepContext context, RunFile document) {
            StringBuilder question = new StringBuilder("Task \"").append(context.task().name()).append("\", step \"")
                    .append(context.step().id()).append("\", the document \"").append(document.name()).append("\". ")
                    .append(TaskPrompts.now(ZonedDateTime.now(zone)));
            if (context.step().agent().instructions() != null) {
                question.append("\n\n").append(context.step().agent().instructions());
            }
            question.append(TaskPrompts.fields(context.step().extract().fields()));
            if (context.step().extract().knowledge()) {
                question.append("\n\nThe company's own rules (e.g. how to account) may be in its knowledge: search it "
                        + "with similarity_search when a field depends on them.");
            }
            return question.toString();
        }
    }

    private String asJson(Map<String, Object> values) {
        try {
            return json.writeValueAsString(values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String joined(@Nullable String first, String second) {
        return first == null ? second : first + " - " + second;
    }
}
