package de.alpdesk.services.a2a;

import jakarta.inject.Singleton;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.auth.TaskOperation;

/**
 * A task of the tenant's assistant belongs to the MCP key that started it (docs/architecture/a2a.md "Tasks"): another
 * key - even of the same tenant - does not find it, as the client software's conversations belong to their API key.
 */
@Singleton
public class KeyOwnsTask implements TaskAuthorizationProvider {

    private final JdbcTaskStore store;

    public KeyOwnsTask(JdbcTaskStore store) {
        this.store = store;
    }

    @Override
    public boolean checkRead(ServerCallContext context, String taskId, TaskOperation operation) {
        Long keyId = (Long) context.getState().get(A2aAgentCore.MCP_KEY_ID);
        if (operation == TaskOperation.LIST_TASKS && store.owner(taskId).isEmpty()) {
            // The list as a whole (the SDK asks with a scope id, not a task's): the store lists the key's tasks only.
            return keyId != null;
        }
        return keyId != null && store.owner(taskId).map(owner -> owner.mcpKeyId().equals(keyId)).orElse(false);
    }

    @Override
    public boolean checkWrite(ServerCallContext context, String taskId, TaskOperation operation) {
        return checkRead(context, taskId, operation);
    }

    @Override
    public boolean checkCreate(ServerCallContext context, TaskOperation operation) {
        return context.getState().get(A2aAgentCore.MCP_KEY_ID) != null;
    }

    @Override
    public boolean isTaskRecorded(String taskId) {
        return store.owner(taskId).isPresent();
    }

    @Override
    public void recordOwnership(ServerCallContext context, String taskId, TaskOperation operation) {
        store.own(taskId, (Long) context.getState().get(A2aAgentCore.TENANT_ID),
                (Long) context.getState().get(A2aAgentCore.MCP_KEY_ID));
    }
}
