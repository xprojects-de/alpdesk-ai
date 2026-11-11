package de.alpdesk.services.assistant;

import de.alpdesk.dto.assistant.AssistantSettings;
import de.alpdesk.dto.assistant.ChatTemplateRequest;
import de.alpdesk.dto.assistant.ModelOption;
import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.entities.assistant.AnswerSource;
import de.alpdesk.entities.assistant.ChatTemplate;
import de.alpdesk.entities.assistant.Creativity;
import de.alpdesk.entities.assistant.Reasoning;
import de.alpdesk.entities.assistant.TenantAssistant;
import de.alpdesk.repositories.assistant.ChatTemplateRepository;
import de.alpdesk.repositories.assistant.TenantAssistantRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How a tenant's assistant answers (docs/architecture/ai-features.md "Assistant settings and templates"): the tenant's
 * instructions, sent with every question, and its defaults - answer source, creativity, reasoning, search results -
 * and the templates its users choose in the chat, each with instructions and settings of its own. Kept by the tenant
 * admins and the admin.
 */
@Singleton
public class AssistantService {

    public static final int MAX_INSTRUCTIONS = 4000;
    public static final int MAX_NAME = 80;
    public static final int MIN_SEARCH_RESULTS = 3;
    public static final int MAX_SEARCH_RESULTS = 20;
    /** A tenant without settings of its own. */
    static final AssistantSettings DEFAULTS =
            new AssistantSettings("", AnswerSource.DOCUMENTS_ONLY, Creativity.MEDIUM, Reasoning.LOW, 5, null, null);

    private final TenantAssistantRepository settings;
    private final ChatTemplateRepository templates;
    private final TenantRepository tenants;
    private final TenantModelService models;

    public AssistantService(TenantAssistantRepository settings, ChatTemplateRepository templates, TenantRepository tenants,
                            TenantModelService models) {
        this.settings = settings;
        this.templates = templates;
        this.tenants = tenants;
        this.models = models;
    }

    /**
     * The tenant's settings, the defaults while it has none; 404 for an unknown tenant. A chosen default or image model
     * the tenant can no longer use (taken away, deactivated) is left out - {@link #imageModel} ignores it, and
     * {@link #defaultModelId} refuses it: the settings are written back whole, and that id would make every later save
     * fail.
     */
    public AssistantSettings settings(Long tenantId) {
        requireTenant(tenantId);
        AssistantSettings stored = settings.findById(tenantId).map(AssistantSettings::from).orElse(DEFAULTS);
        return new AssistantSettings(stored.instructions(), stored.answerSource(), stored.creativity(), stored.reasoning(),
                stored.searchResults(), usableOrNull(tenantId, stored.defaultModelId(), ModelKind.CHAT),
                usableOrNull(tenantId, stored.imageModelId(), ModelKind.IMAGE));
    }

    /**
     * Replaces the tenant's settings. 400 for a missing value, instructions too long, search results out of range, a
     * default model that is not an active chat model of the tenant or an image model that is not one of its image models.
     */
    @Transactional
    public AssistantSettings saveSettings(Long tenantId, AssistantSettings request) {
        requireTenant(tenantId);
        if (request == null || request.answerSource() == null || request.creativity() == null
                || request.reasoning() == null || request.searchResults() == null) {
            throw ServiceException.badRequest("assistant.settingsRequired",
                    "Answer source, creativity, reasoning and search results are required");
        }
        if (request.defaultModelId() != null) {
            models.requireUsable(tenantId, request.defaultModelId(), ModelKind.CHAT);
        }
        if (request.imageModelId() != null) {
            models.requireUsable(tenantId, request.imageModelId(), ModelKind.IMAGE);
        }
        TenantAssistant assistant = new TenantAssistant(tenantId, instructions(request.instructions()),
                request.answerSource(), request.creativity(), request.reasoning(), searchResults(request.searchResults()),
                request.defaultModelId(), request.imageModelId(), Instant.now());
        return AssistantSettings.from(settings.existsById(tenantId) ? settings.update(assistant) : settings.save(assistant));
    }

    /** The chat models the tenant may use - to choose its default model from; 404 for an unknown tenant. */
    public List<ModelOption> chatModels(Long tenantId) {
        requireTenant(tenantId);
        return usable(tenantId, ModelKind.CHAT).stream()
                .map(model -> new ModelOption(model.id(), model.displayName()))
                .toList();
    }

    /** The image models the tenant may use - to choose the chat's from; 404 for an unknown tenant. */
    public List<ModelOption> imageModels(Long tenantId) {
        requireTenant(tenantId);
        return usable(tenantId, ModelKind.IMAGE).stream()
                .map(model -> new ModelOption(model.id(), model.displayName()))
                .toList();
    }

    /**
     * The image model the tenant's chat edits images with (docs/roadmap.md point 25): the one chosen in the settings
     * while it is usable, else the only one the tenant may use - the admin allowing it is the decision, the tenant admin
     * need not choose it as well. Empty with none, or with several and none of them chosen: then the chat has no
     * image tool.
     */
    public Optional<AiModel> imageModel(Long tenantId) {
        List<AiModel> usable = usable(tenantId, ModelKind.IMAGE);
        Long chosenId = settings.findById(tenantId).map(TenantAssistant::imageModelId).orElse(null);
        Optional<AiModel> chosen = usable.stream().filter(model -> model.id().equals(chosenId)).findFirst();
        if (chosen.isPresent()) {
            return chosen;
        }
        return usable.size() == 1 ? Optional.of(usable.getFirst()) : Optional.empty();
    }

    private List<AiModel> usable(Long tenantId, ModelKind kind) {
        return models.findUsable(tenantId).stream().filter(model -> model.kind() == kind).toList();
    }

    private Long usableOrNull(Long tenantId, Long modelId, ModelKind kind) {
        if (modelId == null) {
            return null;
        }
        return usable(tenantId, kind).stream().anyMatch(model -> model.id().equals(modelId)) ? modelId : null;
    }

    /**
     * The model of a question that chooses none (the MCP server): the tenant's default model. 409 while none is chosen,
     * 400 when it is no longer usable (deactivated, no longer assigned).
     */
    public Long defaultModelId(Long tenantId) {
        Long modelId = settings.findById(tenantId).map(TenantAssistant::defaultModelId).orElse(null);
        if (modelId == null) {
            throw ServiceException.conflict("assistant.noDefaultModel",
                    "No default model chosen - a tenant admin chooses one in the assistant settings");
        }
        models.requireUsable(tenantId, modelId, ModelKind.CHAT);
        return modelId;
    }

    /** The tenant's templates by name; 404 for an unknown tenant. */
    public List<ChatTemplate> templates(Long tenantId) {
        requireTenant(tenantId);
        return templates.findByTenantIdOrderByName(tenantId);
    }

    /** 400 for invalid input, 409 when the tenant has a template of this name. */
    @Transactional
    public ChatTemplate createTemplate(Long tenantId, ChatTemplateRequest request) {
        requireTenant(tenantId);
        validate(request);
        Instant now = Instant.now();
        return templates.save(new ChatTemplate(null, tenantId, request.name().strip(), instructions(request.instructions()),
                request.answerSource(), request.creativity(), request.reasoning(), optionalSearchResults(request), now, now));
    }

    /** Empty for another tenant's or an unknown template; else as {@link #createTemplate}. */
    @Transactional
    public Optional<ChatTemplate> updateTemplate(Long tenantId, Long templateId, ChatTemplateRequest request) {
        Optional<ChatTemplate> existing = templates.findByIdAndTenantId(templateId, tenantId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        validate(request);
        return Optional.of(templates.update(new ChatTemplate(templateId, tenantId, request.name().strip(),
                instructions(request.instructions()), request.answerSource(), request.creativity(), request.reasoning(),
                optionalSearchResults(request), existing.get().createdAt(), Instant.now())));
    }

    /**
     * Deletes the template; a conversation that used it goes on with the tenant's settings (the chat shows "Standard").
     */
    @Transactional
    public boolean deleteTemplate(Long tenantId, Long templateId) {
        return templates.deleteByIdAndTenantId(templateId, tenantId) > 0;
    }

    /**
     * What one question is answered with: the tenant's settings, those the template sets instead, and both
     * instructions. 400 for a template that is not the tenant's.
     */
    public Assistant forQuestion(Long tenantId, @Nullable Long templateId) {
        AssistantSettings tenant = settings.findById(tenantId).map(AssistantSettings::from).orElse(DEFAULTS);
        if (templateId == null) {
            return new Assistant(tenant.instructions(), null, null, null, tenant.answerSource(), tenant.creativity(),
                    tenant.reasoning(), tenant.searchResults());
        }
        ChatTemplate template = templates.findByIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> ServiceException.badRequest("template.unknown", "Unknown template"));
        return new Assistant(tenant.instructions(), template.id(), template.name(), template.instructions(),
                orElse(template.answerSource(), tenant.answerSource()), orElse(template.creativity(), tenant.creativity()),
                orElse(template.reasoning(), tenant.reasoning()), orElse(template.searchResults(), tenant.searchResults()));
    }

    /**
     * As {@link #forQuestion} for a conversation going on with its own template: one deleted meanwhile falls back to the
     * tenant's settings - the user can still decide the conversation's waiting call.
     */
    public Assistant forConversation(Long tenantId, @Nullable Long templateId) {
        boolean kept = templateId != null && templates.findByIdAndTenantId(templateId, tenantId).isPresent();
        return forQuestion(tenantId, kept ? templateId : null);
    }

    private static void validate(ChatTemplateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ServiceException.badRequest("template.nameRequired", "The name is required");
        }
        if (request.name().strip().length() > MAX_NAME) {
            throw ServiceException.badRequest("template.nameTooLong", "The name may have at most " + MAX_NAME
                    + " characters",
                    Map.of("max", String.valueOf(MAX_NAME)));
        }
        instructions(request.instructions());
        optionalSearchResults(request);
    }

    private static String instructions(@Nullable String instructions) {
        String text = instructions == null ? "" : instructions.strip();
        if (text.length() > MAX_INSTRUCTIONS) {
            throw ServiceException.badRequest("assistant.instructionsTooLong", "The instructions may have at most "
                    + MAX_INSTRUCTIONS + " characters",
                    Map.of("max", String.valueOf(MAX_INSTRUCTIONS)));
        }
        return text;
    }

    private static Integer optionalSearchResults(ChatTemplateRequest request) {
        return request.searchResults() == null ? null : searchResults(request.searchResults());
    }

    private static int searchResults(int count) {
        if (count < MIN_SEARCH_RESULTS || count > MAX_SEARCH_RESULTS) {
            throw ServiceException.badRequest("assistant.searchResultsOutOfRange", "Search results must be between "
                    + MIN_SEARCH_RESULTS + " and " + MAX_SEARCH_RESULTS,
                    Map.of("min", String.valueOf(MIN_SEARCH_RESULTS), "max", String.valueOf(MAX_SEARCH_RESULTS)));
        }
        return count;
    }

    private static <T> T orElse(@Nullable T own, T tenants) {
        return own != null ? own : tenants;
    }

    private void requireTenant(Long tenantId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw ServiceException.notFound("tenant.notFound", "Tenant not found");
        }
    }

    /**
     * The settings one question is answered with. templateId/templateName/templateInstructions: null without a
     * template.
     */
    public record Assistant(String instructions, @Nullable Long templateId, @Nullable String templateName,
                            @Nullable String templateInstructions, AnswerSource answerSource, Creativity creativity,
                            Reasoning reasoning, int searchResults) {
    }
}
