package de.alpdesk.repositories.task;

import de.alpdesk.entities.task.Task;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface TaskRepository extends CrudRepository<Task, Long> {

    List<Task> findByTenantIdOrderByName(Long tenantId);

    Optional<Task> findByIdAndTenantId(Long id, Long tenantId);

    int deleteByIdAndTenantId(Long id, Long tenantId);

    /** The task, locked until the transaction ends - a run being started (findByIdForKeyShare) waits for it. */
    @Query("SELECT * FROM task WHERE id = :id AND tenant_id = :tenantId FOR UPDATE")
    Optional<Task> findByIdAndTenantIdForUpdate(Long id, Long tenantId);

    /** The task, kept from being deleted until the transaction ends; a save of it does not wait (FOR KEY SHARE). */
    @Query("SELECT * FROM task WHERE id = :id FOR KEY SHARE")
    Optional<Task> findByIdForKeyShare(Long id);

    /** The tenant's own tasks only: another tenant's store id must not reveal that tenant's task names. */
    @Query("SELECT t.name FROM task t JOIN task_file_store s ON s.task_id = t.id "
            + "WHERE s.file_store_id = :storeId AND t.tenant_id = :tenantId ORDER BY t.name")
    List<String> findNamesReadingStore(Long storeId, Long tenantId);

    @Query("DELETE FROM task_file_store WHERE task_id = :taskId")
    void deleteStores(Long taskId);

    @Query("INSERT INTO task_file_store (task_id, file_store_id) VALUES (:taskId, :storeId) ON CONFLICT DO NOTHING")
    void addStore(Long taskId, Long storeId);

    /** The tenants that have tasks - only their databases can hold runs. */
    @Query("SELECT DISTINCT tenant_id FROM task")
    List<Long> findTenantIdsWithTasks();

    /** The active tasks whose schedule is due, of every tenant - the scheduler decides per tenant. */
    @Query("SELECT * FROM task WHERE active AND next_run_at <= :now ORDER BY next_run_at")
    List<Task> findDue(Instant now);

    /** Only if the task is still due then - a save meanwhile computed its own next time. */
    @Query("UPDATE task SET next_run_at = :next WHERE id = :id AND next_run_at = :due")
    int moveNextRun(Long id, Instant due, @Nullable Instant next);

    @Query("SELECT task_id FROM tenant_api_key_task WHERE api_key_id = :apiKeyId ORDER BY task_id")
    List<Long> findTaskIdsOfApiKey(Long apiKeyId);

    @Query("DELETE FROM tenant_api_key_task WHERE api_key_id = :apiKeyId")
    void unassignApiKey(Long apiKeyId);

    @Query("INSERT INTO tenant_api_key_task (api_key_id, task_id) VALUES (:apiKeyId, :taskId) ON CONFLICT DO NOTHING")
    void assignApiKey(Long apiKeyId, Long taskId);

    @Query("SELECT task_id FROM tenant_mcp_key_task WHERE mcp_key_id = :mcpKeyId ORDER BY task_id")
    List<Long> findTaskIdsOfMcpKey(Long mcpKeyId);

    @Query("""
            SELECT t.mcp_key_id, t.task_id FROM tenant_mcp_key_task t
            JOIN tenant_mcp_key k ON k.id = t.mcp_key_id WHERE k.tenant_id = :tenantId ORDER BY t.task_id""")
    List<McpKeyTaskRow> findMcpKeyTasksOfTenant(Long tenantId);

    @Query("DELETE FROM tenant_mcp_key_task WHERE mcp_key_id = :mcpKeyId")
    void unassignMcpKey(Long mcpKeyId);

    @Query("INSERT INTO tenant_mcp_key_task (mcp_key_id, task_id) VALUES (:mcpKeyId, :taskId) ON CONFLICT DO NOTHING")
    void assignMcpKey(Long mcpKeyId, Long taskId);

    /** The names of the API keys and MCP keys the task is assigned to - shown with the task. */
    @Query("""
            SELECT 'API' AS kind, k.name FROM tenant_api_key_task a JOIN tenant_api_key k ON k.id = a.api_key_id
            WHERE a.task_id = :taskId
            UNION ALL
            SELECT 'MCP' AS kind, k.name FROM tenant_mcp_key_task m JOIN tenant_mcp_key k ON k.id = m.mcp_key_id
            WHERE m.task_id = :taskId
            ORDER BY kind, name""")
    List<AssignedKeyRow> findKeysOfTask(Long taskId);

    /** A task an MCP key may use. */
    @Introspected
    record McpKeyTaskRow(Long mcpKeyId, Long taskId) {
    }

    /** A key a task is assigned to: kind API or MCP, its name. */
    @Introspected
    record AssignedKeyRow(String kind, String name) {
    }
}
