package de.alpdesk.repositories.chat;

import de.alpdesk.entities.chat.Conversation;
import de.alpdesk.entities.chat.ConversationOwner;
import de.alpdesk.entities.chat.Message;
import de.alpdesk.entities.chat.MessageRole;
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
 * The tables "conversation" and "message" of a tenant database, by plain JDBC (see
 * repositories.document.DocumentRepository). A conversation is only ever read together with its owner
 * ({@link ConversationOwner}): a user's alone, or one API key's of the client software (no user) - a user's apart from
 * every key's, one key's from another's.
 */
@Singleton
public class ConversationRepository {

    /**
     * The owner's conversations as a condition with one parameter ({@link #ownerId}) - written with "=" so the indexes
     * on user_id and api_key_id are used. An owner without either (the MCP server) has none.
     */
    static String ofOwner(String alias, ConversationOwner owner) {
        return owner.userId() != null ? alias + "user_id = ? AND " + alias + "api_key_id IS NULL"
                : alias + "user_id IS NULL AND " + alias + "api_key_id = ?";
    }

    static Long ownerId(ConversationOwner owner) {
        return owner.userId() != null ? owner.userId() : owner.apiKeyId();
    }

    public Conversation create(DataSource dataSource, ConversationOwner owner, String title) {
        Timestamp now = Timestamp.from(Instant.now());
        String sql = "INSERT INTO conversation (user_id, api_key_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?) "
                + "RETURNING id, user_id, title, template_id, created_at, updated_at, excluded_connection_ids, excluded_skill_ids";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, owner.userId(), Types.BIGINT);
            statement.setObject(2, owner.apiKeyId(), Types.BIGINT);
            statement.setString(3, title);
            statement.setTimestamp(4, now);
            statement.setTimestamp(5, now);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return mapConversation(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the conversation", e);
        }
    }

    /** The conversation's title - written by its chat model after the first question (services.chat.ConversationTitles). */
    public void rename(DataSource dataSource, Long id, String title) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE conversation SET title = ? WHERE id = ?")) {
            statement.setString(1, title);
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not rename the conversation", e);
        }
    }

    /** The connected software the conversation's questions leave out - empty: none (stored as NULL). */
    public void setExcludedConnections(DataSource dataSource, Long id, List<Long> connectionIds) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversation SET excluded_connection_ids = ? WHERE id = ?")) {
            if (connectionIds.isEmpty()) {
                statement.setNull(1, Types.ARRAY);
            } else {
                statement.setArray(1, connection.createArrayOf("bigint", connectionIds.toArray()));
            }
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the conversation's choice of software", e);
        }
    }

    public List<Conversation> findByOwner(DataSource dataSource, ConversationOwner owner) {
        return conversations(dataSource, "SELECT * FROM conversation WHERE " + ofOwner("", owner)
                + " ORDER BY updated_at DESC, id DESC", ownerId(owner));
    }

    public Optional<Conversation> findByIdAndOwner(DataSource dataSource, Long id, ConversationOwner owner) {
        return conversations(dataSource, "SELECT * FROM conversation WHERE id = ? AND " + ofOwner("", owner), id,
                ownerId(owner)).stream().findFirst();
    }

    /** Deletes the conversation with its messages (ON DELETE CASCADE). */
    public boolean delete(DataSource dataSource, Long id, ConversationOwner owner) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM conversation WHERE id = ? AND " + ofOwner("", owner))) {
            statement.setLong(1, id);
            statement.setObject(2, ownerId(owner), Types.BIGINT);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the conversation", e);
        }
    }

    public List<Message> findMessages(DataSource dataSource, Long conversationId) {
        return messages(dataSource, "SELECT * FROM message WHERE conversation_id = ? ORDER BY id", conversationId);
    }

    /** The conversation's last messages, oldest first - the history a new question is sent with. */
    public List<Message> findLastMessages(DataSource dataSource, Long conversationId, int limit) {
        return messages(dataSource, "SELECT * FROM (SELECT * FROM message WHERE conversation_id = ? ORDER BY id DESC "
                + "LIMIT ?) AS last ORDER BY id", conversationId, limit);
    }

    /**
     * Stores a question and its answer together (one transaction) and marks the conversation as used - with the
     * template the question was asked with (null: none).
     */
    public List<Message> addExchange(DataSource dataSource, Long conversationId, String question, String answer,
                                     String modelName, Long templateId) {
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Message asked = insertMessage(connection, conversationId, MessageRole.USER, question, null, now);
                Message answered = insertMessage(connection, conversationId, MessageRole.ASSISTANT, answer, modelName, now);
                try (PreparedStatement touch = connection.prepareStatement(
                        "UPDATE conversation SET updated_at = ?, template_id = ? WHERE id = ?")) {
                    touch.setTimestamp(1, Timestamp.from(now));
                    touch.setObject(2, templateId, Types.BIGINT);
                    touch.setLong(3, conversationId);
                    touch.executeUpdate();
                }
                connection.commit();
                return List.of(asked, answered);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the messages", e);
        }
    }

    /**
     * A question came for the conversation: marked as used before its answer is there, so an answer of an earlier
     * question stored later sees that the conversation went on.
     */
    public void markAsked(DataSource dataSource, Long conversationId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement touch = connection.prepareStatement("UPDATE conversation SET updated_at = ? WHERE id = ?")) {
            touch.setTimestamp(1, Timestamp.from(Instant.now()));
            touch.setLong(2, conversationId);
            touch.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark the conversation as asked", e);
        }
    }

    /** Stores an answer alone - the model went on after the user decided a changing call - and marks the conversation used. */
    public Message addAnswer(DataSource dataSource, Long conversationId, String answer, String modelName) {
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Message answered = insertMessage(connection, conversationId, MessageRole.ASSISTANT, answer, modelName, now);
                try (PreparedStatement touch = connection.prepareStatement("UPDATE conversation SET updated_at = ? WHERE id = ?")) {
                    touch.setTimestamp(1, Timestamp.from(now));
                    touch.setLong(2, conversationId);
                    touch.executeUpdate();
                }
                connection.commit();
                return answered;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the answer", e);
        }
    }

    private static Message insertMessage(Connection connection, Long conversationId, MessageRole role, String content,
                                         String modelName, Instant now) throws SQLException {
        String sql = "INSERT INTO message (conversation_id, role, content, model_name, created_at) VALUES (?, ?, ?, ?, ?) "
                + "RETURNING *";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, conversationId);
            statement.setString(2, role.name());
            statement.setString(3, content);
            statement.setString(4, modelName);
            statement.setTimestamp(5, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return mapMessage(result);
            }
        }
    }

    private List<Conversation> conversations(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                // Typed, so a null user or key id still compares as a number.
                statement.setObject(i + 1, parameters[i], Types.BIGINT);
            }
            List<Conversation> conversations = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    conversations.add(mapConversation(result));
                }
            }
            return conversations;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the conversations", e);
        }
    }

    private List<Message> messages(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            List<Message> messages = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    messages.add(mapMessage(result));
                }
            }
            return messages;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the messages", e);
        }
    }

    private static Conversation mapConversation(ResultSet result) throws SQLException {
        return new Conversation(result.getLong("id"), result.getObject("user_id", Long.class), result.getString("title"),
                result.getObject("template_id", Long.class), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant(), longs(result.getArray("excluded_connection_ids")),
                texts(result.getArray("excluded_skill_ids")));
    }

    private static List<String> texts(java.sql.Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add((String) value);
        }
        return List.copyOf(values);
    }

    /** The agent's skills the conversation's questions leave out - empty: none (stored as NULL). */
    public void setExcludedSkills(DataSource dataSource, Long id, List<String> skillIds) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversation SET excluded_skill_ids = ? WHERE id = ?")) {
            if (skillIds.isEmpty()) {
                statement.setNull(1, Types.ARRAY);
            } else {
                statement.setArray(1, connection.createArrayOf("text", skillIds.toArray()));
            }
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the conversation's choice of skills", e);
        }
    }

    private static List<Long> longs(java.sql.Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add(((Number) value).longValue());
        }
        return List.copyOf(values);
    }

    private static Message mapMessage(ResultSet result) throws SQLException {
        return new Message(result.getLong("id"), result.getLong("conversation_id"),
                MessageRole.valueOf(result.getString("role")), result.getString("content"), result.getString("model_name"),
                result.getTimestamp("created_at").toInstant(), result.getBoolean("a2a_waiting"));
    }

    /**
     * Where the tenant's A2A agent is with the conversation (docs/architecture/a2a.md): its context, and the task that
     * waits for the user's answer to its question back - each null for none.
     */
    public record AgentState(@Nullable String contextId, @Nullable String taskId) {
    }

    public AgentState agentState(DataSource dataSource, Long conversationId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT a2a_context_id, a2a_task_id FROM conversation WHERE id = ?")) {
            statement.setLong(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new AgentState(result.getString(1), result.getString(2)) : new AgentState(null, null);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the conversation's agent", e);
        }
    }

    public void setAgentState(DataSource dataSource, Long conversationId, AgentState state) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversation SET a2a_context_id = ?, a2a_task_id = ? WHERE id = ?")) {
            statement.setString(1, state.contextId());
            statement.setString(2, state.taskId());
            statement.setLong(3, conversationId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the conversation's agent", e);
        }
    }

    /** Forgets the agent's task the conversation waited on - only if it is still that one. */
    public void forgetTask(DataSource dataSource, Long conversationId, String taskId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversation SET a2a_task_id = NULL WHERE id = ? AND a2a_task_id = ?")) {
            statement.setLong(1, conversationId);
            statement.setString(2, taskId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not forget the conversation's agent task", e);
        }
    }

    /** Marks the answer as the agent's question back. */
    public void markWaiting(DataSource dataSource, Long messageId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE message SET a2a_waiting = true WHERE id = ?")) {
            statement.setLong(1, messageId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark the answer", e);
        }
    }
}
