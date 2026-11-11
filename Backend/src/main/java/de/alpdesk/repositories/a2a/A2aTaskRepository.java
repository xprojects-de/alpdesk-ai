package de.alpdesk.repositories.a2a;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The A2A tasks of a tenant's assistant in its database (docs/architecture/a2a.md "Tasks") - plain JDBC on the tenant's
 * DataSource. A task is kept as the SDK's JSON; state, context, key and run beside it to find it by.
 */
@Singleton
public class A2aTaskRepository {

    /** A stored task: its JSON and what it is found by. */
    public record Row(String id, Long mcpKeyId, String state, @Nullable String contextId, @Nullable Long runId,
                      String task, Instant createdAt, Instant updatedAt) {
    }

    public void save(DataSource dataSource, String id, Long mcpKeyId, String state, @Nullable String contextId,
                     @Nullable Long runId, String task) {
        String sql = "INSERT INTO a2a_task (id, mcp_key_id, state, context_id, run_id, task, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET state = EXCLUDED.state, "
                + "context_id = EXCLUDED.context_id, run_id = COALESCE(EXCLUDED.run_id, a2a_task.run_id), "
                + "task = EXCLUDED.task, updated_at = EXCLUDED.updated_at";
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setLong(2, mcpKeyId);
            statement.setString(3, state);
            statement.setString(4, contextId);
            statement.setObject(5, runId, Types.BIGINT);
            statement.setString(6, task);
            statement.setTimestamp(7, now);
            statement.setTimestamp(8, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the A2A task", e);
        }
    }

    public Optional<Row> find(DataSource dataSource, String id) {
        return rows(dataSource, "SELECT * FROM a2a_task WHERE id = ?", id).stream().findFirst();
    }

    private static final String OF_KEY = "mcp_key_id = ? AND (?::text IS NULL OR context_id = ?) "
            + "AND (?::text IS NULL OR state = ?) AND (?::timestamptz IS NULL OR updated_at > ?)";

    /** The key's tasks, the last changed first - filtered by context, state and time, a page of them. */
    public List<Row> findOfKey(DataSource dataSource, Long mcpKeyId, @Nullable String contextId, @Nullable String state,
                               @Nullable Instant after, int limit, int offset) {
        Timestamp from = after == null ? null : Timestamp.from(after);
        return rows(dataSource, "SELECT * FROM a2a_task WHERE " + OF_KEY + " ORDER BY updated_at DESC, id LIMIT ? OFFSET ?",
                mcpKeyId, contextId, contextId, state, state, from, from, limit, offset);
    }

    public int countOfKey(DataSource dataSource, Long mcpKeyId, @Nullable String contextId, @Nullable String state,
                          @Nullable Instant after) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM a2a_task WHERE " + OF_KEY)) {
            Timestamp from = after == null ? null : Timestamp.from(after);
            statement.setLong(1, mcpKeyId);
            statement.setString(2, contextId);
            statement.setString(3, contextId);
            statement.setString(4, state);
            statement.setString(5, state);
            statement.setTimestamp(6, from);
            statement.setTimestamp(7, from);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the A2A tasks", e);
        }
    }

    public void delete(DataSource dataSource, String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM a2a_task WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the A2A task", e);
        }
    }

    /** Which of these tasks exist. */
    public List<String> existing(DataSource dataSource, List<String> ids) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM a2a_task WHERE id = ANY(?)")) {
            statement.setArray(1, connection.createArrayOf("text", ids.toArray()));
            List<String> existing = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    existing.add(result.getString(1));
                }
            }
            return existing;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not look up the A2A tasks", e);
        }
    }

    /** Deletes the tasks last changed before the time - their ids, for the index. */
    public List<String> deleteChangedBefore(DataSource dataSource, Instant before) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM a2a_task WHERE updated_at < ? RETURNING id")) {
            statement.setTimestamp(1, Timestamp.from(before));
            List<String> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getString(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the old A2A tasks", e);
        }
    }

    private List<Row> rows(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            List<Row> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Row(result.getString("id"), result.getLong("mcp_key_id"), result.getString("state"),
                            result.getString("context_id"), result.getObject("run_id", Long.class),
                            result.getString("task"), result.getTimestamp("created_at").toInstant(),
                            result.getTimestamp("updated_at").toInstant()));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the A2A tasks", e);
        }
    }
}
