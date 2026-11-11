package de.alpdesk.repositories.chat;

import de.alpdesk.entities.chat.ConversationFile;
import de.alpdesk.entities.chat.ConversationOwner;
import de.alpdesk.entities.chat.FileOrigin;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The table "conversation_file" of a tenant database, by plain JDBC (see repositories.document.DocumentRepository).
 * A file is only ever read together with its conversation's user (null: the client software), like the conversation.
 */
@Singleton
public class ConversationFileRepository {

    private static final String COLUMNS = "f.id, f.conversation_id, f.message_id, f.origin, f.file_name, f.content_type, "
            + "f.size_bytes, f.storage_dir, f.image_count, f.created_at, f.expired_at";

    /** The text of a file (null for an image) - a new file in a conversation the caller has already checked. */
    public ConversationFile insert(DataSource dataSource, Long conversationId, Long messageId, FileOrigin origin,
                                   String fileName, String contentType, long sizeBytes, String storageDir, String text,
                                   int imageCount) {
        String sql = "INSERT INTO conversation_file AS f (conversation_id, message_id, origin, file_name, content_type, "
                + "size_bytes, storage_dir, text, image_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING "
                + COLUMNS;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, conversationId);
            statement.setObject(2, messageId, Types.BIGINT);
            statement.setString(3, origin.name());
            statement.setString(4, fileName);
            statement.setString(5, contentType);
            statement.setLong(6, sizeBytes);
            statement.setString(7, storageDir);
            statement.setString(8, text);
            statement.setInt(9, imageCount);
            statement.setTimestamp(10, Timestamp.from(Instant.now()));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return map(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the file", e);
        }
    }

    public List<ConversationFile> findByConversation(DataSource dataSource, Long conversationId) {
        return query(dataSource, "SELECT " + COLUMNS + " FROM conversation_file f WHERE f.conversation_id = ? ORDER BY f.id",
                conversationId);
    }

    /** One of the user's files (null: the client software's) - empty for a file of another conversation's owner. */
    public Optional<ConversationFile> findByIdAndOwner(DataSource dataSource, Long id, ConversationOwner owner) {
        return query(dataSource, "SELECT " + COLUMNS + " FROM conversation_file f JOIN conversation c ON c.id = "
                + "f.conversation_id WHERE " + ConversationRepository.ofOwner("c.", owner) + " AND f.id = ?",
                ConversationRepository.ownerId(owner), id).stream().findFirst();
    }

    /** The file's text - empty for one without (an image). Only this file's: a text may have millions of characters. */
    public Optional<String> findText(DataSource dataSource, Long id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT text FROM conversation_file WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the file's text", e);
        }
    }

    /** The ids of the conversation's files that have a text - without reading the texts. */
    public Set<Long> findIdsWithText(DataSource dataSource, Long conversationId) {
        Set<Long> ids = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM conversation_file WHERE conversation_id = ? AND text IS NOT NULL")) {
            statement.setLong(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read which files have a text", e);
        }
    }

    /**
     * The uploaded videos of every conversation stored before this moment and not expired yet - for the cleanup job, on
     * a connection of its own that the caller closes (it reads every tenant one after the other, no pool).
     */
    public List<ConversationFile> findVideosToExpire(Connection connection, Instant uploadedBefore) {
        return findToExpire(connection, "f.origin = 'UPLOADED' AND f.content_type LIKE 'video/%'", uploadedBefore);
    }

    /** Every other file (not an uploaded video) stored before this moment and not expired yet - as findVideosToExpire. */
    public List<ConversationFile> findFilesToExpire(Connection connection, Instant storedBefore) {
        return findToExpire(connection, "NOT (f.origin = 'UPLOADED' AND f.content_type LIKE 'video/%')", storedBefore);
    }

    private List<ConversationFile> findToExpire(Connection connection, String kind, Instant before) {
        String sql = "SELECT " + COLUMNS + " FROM conversation_file f WHERE " + kind
                + " AND f.expired_at IS NULL AND f.created_at < ? ORDER BY f.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(before));
            List<ConversationFile> expiring = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    expiring.add(map(result));
                }
            }
            return expiring;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the files to expire", e);
        }
    }

    /**
     * Marks a file whose content was deleted - and drops its text: nothing of it stays but its name, type and size. On
     * the cleanup job's connection, which the caller closes.
     */
    public void markExpired(Connection connection, Long id, Instant when) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversation_file SET expired_at = ?, text = NULL WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.from(when));
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark the file expired", e);
        }
    }

    public boolean delete(DataSource dataSource, Long id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM conversation_file WHERE id = ?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the file", e);
        }
    }

    private List<ConversationFile> query(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                // Typed, so a null user or key id still compares as a number.
                statement.setObject(i + 1, parameters[i], Types.BIGINT);
            }
            List<ConversationFile> files = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    files.add(map(result));
                }
            }
            return files;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the files", e);
        }
    }

    private static ConversationFile map(ResultSet result) throws SQLException {
        return new ConversationFile(result.getLong("id"), result.getLong("conversation_id"),
                result.getObject("message_id", Long.class), FileOrigin.valueOf(result.getString("origin")),
                result.getString("file_name"), result.getString("content_type"), result.getLong("size_bytes"),
                result.getString("storage_dir"), result.getInt("image_count"), result.getTimestamp("created_at").toInstant(),
                instant(result.getTimestamp("expired_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
