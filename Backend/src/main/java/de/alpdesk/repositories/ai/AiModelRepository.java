package de.alpdesk.repositories.ai;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.ai.ModelKind;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface AiModelRepository extends CrudRepository<AiModel, Long> {
    List<AiModel> findAllOrderByKindAscAndDisplayNameAsc();

    /** The model of this name on this server (null: the provider's own). */
    @Query("""
            SELECT * FROM ai_model WHERE provider = :provider AND kind = :kind AND model_name = :modelName
            AND base_url IS NOT DISTINCT FROM :baseUrl""")
    Optional<AiModel> findSame(AiProvider provider, ModelKind kind, String modelName, @Nullable String baseUrl);

    Optional<AiModel> findByTranscribesTrueAndActiveTrue();

    Optional<AiModel> findByReadsScansTrueAndActiveTrue();

    List<AiModel> findByIdIn(Collection<Long> ids);

    /** No model transcribes any more - before another one takes over (one at most, a unique index). */
    @Query("UPDATE ai_model SET transcribes = FALSE WHERE transcribes")
    void clearTranscriber();

    /** No model reads scans any more - before another one takes over (one at most, a unique index). */
    @Query("UPDATE ai_model SET reads_scans = FALSE WHERE reads_scans")
    void clearScanReader();

    /** Whether a tenant embeds its documents with the model - such a model cannot be deleted. */
    @Query("SELECT EXISTS (SELECT 1 FROM tenant WHERE embedding_model_id = :id)")
    boolean isEmbeddingModelOfATenant(Long id);

    /** The ids of the models the admin assigned to the tenant, active or not. */
    @Query("SELECT model_id FROM tenant_model WHERE tenant_id = :tenantId ORDER BY model_id")
    List<Long> findAssignedIds(Long tenantId);

    /** The active models assigned to the tenant. */
    @Query("""
            SELECT ai_model.* FROM ai_model JOIN tenant_model ON tenant_model.model_id = ai_model.id
            WHERE tenant_model.tenant_id = :tenantId AND ai_model.active
            ORDER BY ai_model.kind, ai_model.display_name""")
    List<AiModel> findUsable(Long tenantId);

    @Query("SELECT EXISTS (SELECT 1 FROM tenant_model WHERE tenant_id = :tenantId AND model_id = :modelId)")
    boolean isAssigned(Long tenantId, Long modelId);

    @Query("DELETE FROM tenant_model WHERE tenant_id = :tenantId")
    void unassignAll(Long tenantId);

    @Query("INSERT INTO tenant_model (tenant_id, model_id) VALUES (:tenantId, :modelId)")
    void assign(Long tenantId, Long modelId);
}
