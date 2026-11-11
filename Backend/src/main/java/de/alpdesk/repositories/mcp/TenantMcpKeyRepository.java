package de.alpdesk.repositories.mcp;

import de.alpdesk.entities.mcp.TenantMcpKey;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface TenantMcpKeyRepository extends CrudRepository<TenantMcpKey, Long> {
    List<TenantMcpKey> findByTenantIdOrderByCreatedAt(Long tenantId);

    boolean existsByTenantId(Long tenantId);

    Optional<TenantMcpKey> findByKeyHash(String keyHash);

    /** The key, locked until the transaction ends against another update. */
    @Query("SELECT * FROM tenant_mcp_key WHERE id = :id AND tenant_id = :tenantId FOR NO KEY UPDATE")
    Optional<TenantMcpKey> findByIdAndTenantIdForNoKeyUpdate(Long id, Long tenantId);

    /** Deletes it; 0 when another delete removed it meanwhile (the row lock waited for it). */
    int deleteByIdAndTenantId(Long id, Long tenantId);

    void updateLastUsedAt(@Id Long id, Instant lastUsedAt);

    /** The connected software the key may use (roadmap point 41), ordered by id. */
    @Query("SELECT connection_id FROM tenant_mcp_key_connection WHERE mcp_key_id = :mcpKeyId ORDER BY connection_id")
    List<Long> findConnectionIds(Long mcpKeyId);

    /** The connected software the key may use with its level (READ, WRITE - roadmap point 68). */
    @Query("SELECT connection_id, level FROM tenant_mcp_key_connection WHERE mcp_key_id = :mcpKeyId ORDER BY connection_id")
    List<McpConnectionRepository.AccessRow> findConnectionLevels(Long mcpKeyId);

    @Query("""
            SELECT c.mcp_key_id, c.connection_id, c.level FROM tenant_mcp_key_connection c
            JOIN tenant_mcp_key k ON k.id = c.mcp_key_id WHERE k.tenant_id = :tenantId ORDER BY c.connection_id""")
    List<ConnectionRow> findConnectionsOfTenant(Long tenantId);

    @Query("DELETE FROM tenant_mcp_key_connection WHERE mcp_key_id = :mcpKeyId")
    void unassignConnections(Long mcpKeyId);

    @Query("""
            INSERT INTO tenant_mcp_key_connection (mcp_key_id, connection_id, level)
            VALUES (:mcpKeyId, :connectionId, :level)""")
    void assignConnection(Long mcpKeyId, Long connectionId, String level);

    /** A connection a key may use, with its level. */
    @Introspected
    record ConnectionRow(Long mcpKeyId, Long connectionId, String level) {
    }
}
