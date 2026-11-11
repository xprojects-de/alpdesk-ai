package de.alpdesk.services.a2a;

import de.alpdesk.security.SecretCipher;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationUrlValidator;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The webhooks of the tenants' A2A tasks (docs/architecture/a2a.md "Push notifications"), in the task's tenant database
 * (a2a_push_config, deleted with the task): the SDK's config as its JSON, encrypted - its token and credentials are the
 * client's. At most {@value #MAX_PER_TASK} per task; an address that is not public HTTPS is refused when registered.
 * Who may register one for which task the SDK checks through the key owning the task.
 */
@Singleton
public class JdbcPushConfigStore implements PushNotificationConfigStore {

    static final int MAX_PER_TASK = 5;

    private final JdbcTaskStore tasks;
    private final SecretCipher cipher;
    private final PushNotificationUrlValidator addresses;

    public JdbcPushConfigStore(JdbcTaskStore tasks, SecretCipher cipher, PushAddresses addresses) {
        this.tasks = tasks;
        this.cipher = cipher;
        this.addresses = addresses;
    }

    @Override
    public TaskPushNotificationConfig setInfo(TaskPushNotificationConfig config) {
        try {
            addresses.validate(config.url());
        } catch (IllegalArgumentException e) {
            throw new InvalidParamsError(e.getMessage());
        }
        String taskId = config.taskId();
        String id = config.id() == null || config.id().isBlank() ? taskId : config.id();
        TaskPushNotificationConfig stored = TaskPushNotificationConfig.builder(config).id(id).build();
        DataSource dataSource = tasks.dataSourceOfTask(taskId);
        List<TaskPushNotificationConfig> existing = read(dataSource, taskId);
        boolean replaces = existing.stream().anyMatch(other -> id.equals(other.id()));
        if (!replaces && existing.size() >= MAX_PER_TASK) {
            throw new InvalidParamsError("At most " + MAX_PER_TASK + " webhooks per task");
        }
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO a2a_push_config (task_id, id, config, created_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (task_id, id) DO UPDATE SET config = EXCLUDED.config")) {
            statement.setString(1, taskId);
            statement.setString(2, id);
            statement.setString(3, cipher.encrypt(JsonUtil.toJson(stored)));
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Could not store the webhook", e);
        }
        return stored;
    }

    @Override
    public ListTaskPushNotificationConfigsResult getInfo(ListTaskPushNotificationConfigsParams params) {
        // Asked for every event - also of a task whose owner is not recorded yet, which has no webhook yet either.
        if (!tasks.owned(params.id())) {
            return new ListTaskPushNotificationConfigsResult(List.of(), null);
        }
        DataSource dataSource = tasks.dataSourceOfTask(params.id());
        // No next page: the SDK pages on while a token comes back.
        return new ListTaskPushNotificationConfigsResult(read(dataSource, params.id()), null);
    }

    @Override
    public void deleteInfo(String taskId, String configId) {
        try (Connection connection = tasks.dataSourceOfTask(taskId).getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM a2a_push_config WHERE task_id = ? AND id = ?")) {
            statement.setString(1, taskId);
            statement.setString(2, configId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the webhook", e);
        }
    }

    private List<TaskPushNotificationConfig> read(DataSource dataSource, String taskId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT config FROM a2a_push_config WHERE task_id = ? ORDER BY created_at, id")) {
            statement.setString(1, taskId);
            List<TaskPushNotificationConfig> configs = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    configs.add(JsonUtil.fromJson(cipher.decrypt(result.getString(1)), TaskPushNotificationConfig.class));
                }
            }
            return configs;
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Could not read the webhooks", e);
        }
    }
}
