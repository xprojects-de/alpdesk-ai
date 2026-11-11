package de.alpdesk.repositories.chat;

import de.alpdesk.entities.chat.PendingAction;
import de.alpdesk.entities.chat.PendingActionStatus;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The table "pending_action" of a tenant database, by plain JDBC (see ConversationRepository): the changing tool calls
 * waiting for their user. It is read through its conversation, which belongs to one user.
 */
@Singleton
public class PendingActionRepository {

    public PendingAction create(DataSource dataSource, PendingAction action) {
        String sql = "INSERT INTO pending_action (conversation_id, message_id, model_id, connection_id, tool_id, "
                + "software_name, tool_name, call_id, called_name, arguments, status, created_at, decided_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, action.conversationId());
            statement.setLong(2, action.messageId());
            statement.setLong(3, action.modelId());
            statement.setLong(4, action.connectionId());
            statement.setLong(5, action.toolId());
            statement.setString(6, action.softwareName());
            statement.setString(7, action.toolName());
            statement.setString(8, action.callId());
            statement.setString(9, action.calledName());
            statement.setString(10, action.arguments());
            statement.setString(11, action.status().name());
            statement.setTimestamp(12, Timestamp.from(action.createdAt()));
            statement.setTimestamp(13, action.decidedAt() == null ? null : Timestamp.from(action.decidedAt()));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return map(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the pending action", e);
        }
    }

    public Optional<PendingAction> findById(DataSource dataSource, Long id) {
        return query(dataSource, "SELECT * FROM pending_action WHERE id = ?", id).stream().findFirst();
    }

    public List<PendingAction> findByConversation(DataSource dataSource, Long conversationId) {
        return query(dataSource, "SELECT * FROM pending_action WHERE conversation_id = ? ORDER BY id", conversationId);
    }

    /**
     * Decides a waiting call - only one decision wins: false when it was decided (or expired) meanwhile, so a double
     * click never runs a change twice.
     */
    public boolean decide(DataSource dataSource, Long id, PendingActionStatus status) {
        String sql = "UPDATE pending_action SET status = ?, decided_at = ? WHERE id = ? AND status = 'PENDING'";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not decide the pending action", e);
        }
    }

    /** What the software answered to a call that was run. */
    public void storeResult(DataSource dataSource, Long id, @Nullable String text) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE pending_action SET result = ? WHERE id = ?")) {
            statement.setString(1, text);
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the result of the pending action", e);
        }
    }

    /** A new question in the conversation: the calls still waiting no longer fit it. */
    public void expireOpen(DataSource dataSource, Long conversationId) {
        String sql = "UPDATE pending_action SET status = 'EXPIRED', decided_at = ? WHERE conversation_id = ? AND status = 'PENDING'";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setLong(2, conversationId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not expire the pending actions", e);
        }
    }

    private List<PendingAction> query(DataSource dataSource, String sql, Long parameter) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            List<PendingAction> actions = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actions.add(map(result));
                }
            }
            return actions;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the pending actions", e);
        }
    }

    private static PendingAction map(ResultSet result) throws SQLException {
        Timestamp decided = result.getTimestamp("decided_at");
        return new PendingAction(result.getLong("id"), result.getLong("conversation_id"), result.getLong("message_id"),
                result.getLong("model_id"), result.getLong("connection_id"), result.getLong("tool_id"),
                result.getString("software_name"), result.getString("tool_name"), result.getString("call_id"),
                result.getString("called_name"), result.getString("arguments"),
                PendingActionStatus.valueOf(result.getString("status")), result.getString("result"),
                result.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant());
    }
}
