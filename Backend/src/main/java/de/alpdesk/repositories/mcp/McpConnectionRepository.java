package de.alpdesk.repositories.mcp;

import de.alpdesk.entities.mcp.McpAccessLevel;
import de.alpdesk.entities.mcp.McpConnection;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface McpConnectionRepository extends CrudRepository<McpConnection, Long> {
    List<McpConnection> findByTenantIdOrderByName(Long tenantId);

    Optional<McpConnection> findByIdAndTenantId(Long id, Long tenantId);

    @Query("SELECT * FROM mcp_connection WHERE id = :id AND tenant_id = :tenantId FOR NO KEY UPDATE")
    Optional<McpConnection> findByIdAndTenantIdForNoKeyUpdate(Long id, Long tenantId);

    int deleteByIdAndTenantId(Long id, Long tenantId);

    /**
     * No MCP key may change in the connection any more (roadmap point 68) - when it stops going to MCP clients: a
     * level left would be in force again, unseen, the day it is switched back on.
     */
    @Query("UPDATE tenant_mcp_key_connection SET level = 'READ' WHERE connection_id = :connectionId")
    void mcpKeysReadOnly(Long connectionId);

    /** The connected software an API key of the client software may use (roadmap point 42), ordered by id. */
    @Query("SELECT connection_id FROM tenant_api_key_connection WHERE api_key_id = :apiKeyId ORDER BY connection_id")
    List<Long> findConnectionIdsOfApiKey(Long apiKeyId);

    @Query("""
            SELECT c.api_key_id, c.connection_id FROM tenant_api_key_connection c
            JOIN tenant_api_key k ON k.id = c.api_key_id WHERE k.tenant_id = :tenantId ORDER BY c.connection_id""")
    List<ApiKeyConnectionRow> findApiKeyConnectionsOfTenant(Long tenantId);

    @Query("DELETE FROM tenant_api_key_connection WHERE api_key_id = :apiKeyId")
    void unassignApiKey(Long apiKeyId);

    @Query("INSERT INTO tenant_api_key_connection (api_key_id, connection_id) VALUES (:apiKeyId, :connectionId)")
    void assignApiKey(Long apiKeyId, Long connectionId);

    /** A connection an API key may use. */
    @Introspected
    record ApiKeyConnectionRow(Long apiKeyId, Long connectionId) {
    }

    /** A connection and a level for it. */
    @Introspected
    record AccessRow(Long connectionId, String level) {
        public McpAccessLevel accessLevel() {
            return McpAccessLevel.valueOf(level);
        }
    }
}
