package de.alpdesk.repositories.document;

import de.alpdesk.entities.document.KnowledgeArea;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/** The knowledge areas and the keys assigned to them (management database) - users get theirs through groups. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface KnowledgeAreaRepository extends CrudRepository<KnowledgeArea, Long> {
    List<KnowledgeArea> findByTenantIdOrderByName(Long tenantId);

    Optional<KnowledgeArea> findByIdAndTenantId(Long id, Long tenantId);

    Optional<KnowledgeArea> findByTenantIdAndNameIgnoreCase(Long tenantId, String name);

    int deleteByIdAndTenantId(Long id, Long tenantId);

    @Query("SELECT area_id FROM tenant_api_key_area WHERE api_key_id = :apiKeyId ORDER BY area_id")
    List<Long> findAreaIdsOfApiKey(Long apiKeyId);

    @Query("""
            SELECT a.api_key_id AS owner_id, a.area_id FROM tenant_api_key_area a
            JOIN tenant_api_key k ON k.id = a.api_key_id WHERE k.tenant_id = :tenantId ORDER BY a.area_id""")
    List<Assignment> findApiKeyAssignments(Long tenantId);

    @Query("DELETE FROM tenant_api_key_area WHERE api_key_id = :apiKeyId")
    void unassignApiKey(Long apiKeyId);

    @Query("INSERT INTO tenant_api_key_area (api_key_id, area_id) VALUES (:apiKeyId, :areaId)")
    void assignApiKey(Long apiKeyId, Long areaId);

    @Query("SELECT area_id FROM tenant_mcp_key_area WHERE mcp_key_id = :mcpKeyId ORDER BY area_id")
    List<Long> findAreaIdsOfMcpKey(Long mcpKeyId);

    @Query("""
            SELECT a.mcp_key_id AS owner_id, a.area_id FROM tenant_mcp_key_area a
            JOIN tenant_mcp_key k ON k.id = a.mcp_key_id WHERE k.tenant_id = :tenantId ORDER BY a.area_id""")
    List<Assignment> findMcpKeyAssignments(Long tenantId);

    @Query("DELETE FROM tenant_mcp_key_area WHERE mcp_key_id = :mcpKeyId")
    void unassignMcpKey(Long mcpKeyId);

    @Query("INSERT INTO tenant_mcp_key_area (mcp_key_id, area_id) VALUES (:mcpKeyId, :areaId)")
    void assignMcpKey(Long mcpKeyId, Long areaId);

    /** An area assigned to a key (ownerId: the key's id). */
    @Introspected
    record Assignment(Long ownerId, Long areaId) {
    }
}
