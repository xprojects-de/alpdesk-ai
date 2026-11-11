package de.alpdesk.services.ai;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.ai.AiModelRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which models a tenant may use, assigned by the admin (docs/architecture/ai-features.md "Model access"). A tenant
 * sees, chooses and calls only the active models assigned to it - the transcriber too, so a tenant's audio never goes
 * to a provider it is not allowed.
 */
@Singleton
public class TenantModelService {

    private final AiModelRepository models;
    private final TenantRepository tenants;

    public TenantModelService(AiModelRepository models, TenantRepository tenants) {
        this.models = models;
        this.tenants = tenants;
    }

    public List<Long> findAssignedIds(Long tenantId) {
        return models.findAssignedIds(tenantId);
    }

    /** The active models assigned to the tenant. */
    public List<AiModel> findUsable(Long tenantId) {
        return models.findUsable(tenantId);
    }

    /** An active model of this kind assigned to the tenant - 400 otherwise. */
    public AiModel requireUsable(Long tenantId, Long modelId, ModelKind kind) {
        return Optional.ofNullable(modelId).flatMap(models::findById)
                .filter(model -> model.active() && model.kind() == kind && models.isAssigned(tenantId, model.id()))
                .orElseThrow(() -> ServiceException.badRequest("model.unknownOrUnavailable", "Unknown or unavailable "
                        + kind.name().toLowerCase() + " model",
                        Map.of("kind", kind.name().toLowerCase())));
    }

    /** The active transcriber, if it is assigned to the tenant. */
    public Optional<AiModel> findTranscriber(Long tenantId) {
        return models.findByTranscribesTrueAndActiveTrue().filter(model -> models.isAssigned(tenantId, model.id()));
    }

    /** The active model reading images and scans of documents, if it is assigned to the tenant. */
    public Optional<AiModel> findScanReader(Long tenantId) {
        return models.findByReadsScansTrueAndActiveTrue().filter(model -> models.isAssigned(tenantId, model.id()));
    }

    /**
     * Replaces the tenant's models; empty for an unknown tenant. 400 for an unknown model, 409 when the model the
     * tenant's documents are embedded with is left out: changing it embeds them all again - the tenant decides that. A
     * reranker left out is switched off: the search keeps the hybrid order, nothing is lost.
     */
    @Transactional
    public Optional<List<Long>> assign(Long tenantId, List<Long> modelIds) {
        Optional<Tenant> tenant = tenants.findById(tenantId);
        if (tenant.isEmpty()) {
            return Optional.empty();
        }
        if (modelIds == null || modelIds.contains(null)) {
            throw ServiceException.badRequest("model.idsRequired", "The list of model ids is required");
        }
        Set<Long> ids = new LinkedHashSet<>(modelIds);
        if (models.findByIdIn(ids).size() != ids.size()) {
            throw ServiceException.badRequest("model.unknown", "Unknown model");
        }
        Long embeddingModelId = tenant.get().embeddingModelId();
        if (embeddingModelId != null && !ids.contains(embeddingModelId)) {
            throw ServiceException.conflict("model.embeddingInUseRemove",
                    "The tenant's documents are embedded with this model - it can only be "
                    + "removed once the tenant has chosen another one");
        }
        Long rerankingModelId = tenant.get().rerankingModelId();
        if (rerankingModelId != null && !ids.contains(rerankingModelId)) {
            tenants.updateRerankingModelId(tenantId, null);
        }
        models.unassignAll(tenantId);
        ids.forEach(id -> models.assign(tenantId, id));
        return Optional.of(models.findAssignedIds(tenantId));
    }
}
