package de.alpdesk.services.task;

import de.alpdesk.dto.assistant.ModelOption;
import de.alpdesk.dto.task.TaskDraftModels;
import de.alpdesk.dto.task.TaskDraftRequest;
import de.alpdesk.dto.task.TaskDraftResponse;
import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.entities.task.StartedBy;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.mail.MailSender;
import de.alpdesk.services.ai.AiModels;
import de.alpdesk.services.ai.ChatOptions;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.ai.UsageService;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.filestore.FileStoreService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Draft with AI" (docs/architecture/tasks.md): the tenant's chat model writes a task from a tenant admin's own words -
 * or changes the one given - with the format's reference and the tenant's names, and the check corrects it: its
 * mistakes with their lines go back to the model, at most three rounds within the draft timeout. The result is a draft
 * for the editor - nothing is saved here. Not in a transaction: a provider can take minutes.
 */
@Singleton
public class TaskDraftService {

    static final int MAX_DESCRIPTION = 4000;
    static final int MAX_ROUNDS = 3;
    private static final Pattern YAML_BLOCK = Pattern.compile("```(?:ya?ml)?\\s*\\n(.*?)```", Pattern.DOTALL);
    static final String SYSTEM = "You write scheduled tasks of a company in the task format below - YAML only. Answer "
            + "with the whole task in one yaml code block and nothing else. Use only the names listed under \"What the "
            + "tenant has\" for file stores and models, exactly as written. Give every step an id; an edge back needs "
            + "maxRounds; mail only when something is wrong or worth knowing (send_mail with an if); no anchors or "
            + "aliases. When a task is given to change, change what is asked and keep everything else with its comments.";

    private final TaskService tasks;
    private final TaskCompiler compiler;
    private final TaskReference reference;
    private final TenantModelService models;
    private final ProviderKeyService keys;
    private final AssistantService assistants;
    private final FileStoreService stores;
    private final MailSender mail;
    private final AiModels aiModels;
    private final UsageService usage;
    private final Duration timeout;

    public TaskDraftService(TaskService tasks, TaskCompiler compiler, TaskReference reference, TenantModelService models,
                            ProviderKeyService keys, AssistantService assistants, FileStoreService stores,
                            MailSender mail, AiModels aiModels, UsageService usage,
                            @Value("${app.task.draft-timeout}") Duration timeout) {
        this.tasks = tasks;
        this.compiler = compiler;
        this.reference = reference;
        this.models = models;
        this.keys = keys;
        this.assistants = assistants;
        this.stores = stores;
        this.mail = mail;
        this.aiModels = aiModels;
        this.usage = usage;
        this.timeout = timeout;
    }

    /** The chat models a draft may use, and the one preselected: the assistant's default model. */
    public TaskDraftModels models(Long tenantId, StartedBy caller) {
        tasks.tenant(tenantId, caller);
        List<ModelOption> options = assistants.chatModels(tenantId);
        return new TaskDraftModels(options, assistants.settings(tenantId).defaultModelId());
    }

    /**
     * A draft of the task described. 400 without a description or with one too long, 409 without a model (none chosen,
     * no default model); the provider's failure as the chat's.
     */
    public TaskDraftResponse draft(Long tenantId, StartedBy caller, @Nullable Long userId, TaskDraftRequest request) {
        Tenant tenant = tasks.tenant(tenantId, caller);
        String description = description(request.description());
        AiModel model = models.requireUsable(tenantId,
                request.modelId() != null ? request.modelId() : assistants.defaultModelId(tenantId), ModelKind.CHAT);
        ResolvedKey key = keys.resolve(tenant, model);
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from(system(tenant)),
                UserMessage.from(question(description, request.source()))));
        long deadline = System.nanoTime() + timeout.toNanos();
        String source = "";
        TaskCompiler.Compiled compiled = null;
        int round = 0;
        while (round < MAX_ROUNDS && (round == 0 || System.nanoTime() < deadline)) {
            round++;
            ChatResponse response = aiModels.chat(model, key.apiKey(), messages, List.of(), false, ChatOptions.NONE);
            usage.record(tenantId, userId, model, key.source(), response.tokenUsage(), 0);
            String answer = response.aiMessage().text() == null ? "" : response.aiMessage().text();
            source = yaml(answer);
            compiled = compiler.compile(tenantId, source);
            if (compiled.valid()) {
                break;
            }
            messages.add(AiMessage.from(answer.isBlank() ? "(no answer)" : answer));
            messages.add(UserMessage.from(corrections(compiled.errors())));
        }
        return new TaskDraftResponse(source, compiled.valid(), compiled.errors(),
                compiled.valid() ? TaskService.views(compiled.definition()) : List.of(), round);
    }

    private static String description(@Nullable String description) {
        if (description == null || description.isBlank()) {
            throw ServiceException.badRequest("task.draftDescriptionRequired", "Describe what the task is to do");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw ServiceException.badRequest("task.draftDescriptionTooLong", "The description may have at most "
                    + MAX_DESCRIPTION + " characters", Map.of("max", String.valueOf(MAX_DESCRIPTION)));
        }
        return description.strip();
    }

    /**
     * The rules, the format's reference and the tenant's names - its stores with their newest file names, never what is
     * in a file.
     */
    private String system(Tenant tenant) {
        List<String> chatModels = new ArrayList<>();
        for (AiModel model : models.findUsable(tenant.id())) {
            if (model.active() && model.kind() == ModelKind.CHAT && keys.find(tenant, model).isPresent()) {
                chatModels.add(model.displayName());
            }
        }
        List<String> fileStores = stores.storesForDraft(tenant.id());
        return SYSTEM + "\n\n=== The format ===\n" + reference.markdown(TaskReference.ENGLISH)
                + "\n\n=== What the tenant has ===\nFile stores: " + (fileStores.isEmpty() ? "none" : String.join("; ", fileStores))
                + "\nChat models: " + (chatModels.isEmpty() ? "none" : String.join("; ", chatModels))
                + "\nMail: " + (mail.enabled() ? "configured" : "not configured");
    }

    private static String question(String description, @Nullable String source) {
        String question = "=== What the task is to do ===\n" + description;
        if (source == null || source.isBlank()) {
            return question;
        }
        return question + "\n\n=== The task as it is - change it as asked ===\n```yaml\n" + source.strip() + "\n```";
    }

    /** The task in the answer's yaml code block - the whole answer when it wrote none. */
    static String yaml(String answer) {
        Matcher block = YAML_BLOCK.matcher(answer);
        return (block.find() ? block.group(1) : answer).strip() + "\n";
    }

    private static String corrections(List<TaskError> errors) {
        StringBuilder text = new StringBuilder("The check found these mistakes - write the whole task again, corrected, "
                + "in one yaml code block:");
        for (TaskError error : errors) {
            text.append("\n- ").append(error.line() > 0 ? "line " + error.line() + ": " : "").append(error.message());
        }
        return text.toString();
    }
}
