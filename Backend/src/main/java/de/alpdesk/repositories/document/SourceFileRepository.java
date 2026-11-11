package de.alpdesk.repositories.document;

import de.alpdesk.entities.document.SourceFile;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The table "source_file" of a tenant database, by plain JDBC (docs/architecture/tenancy.md): what each knowledge
 * source saw, file by file. Every method takes the tenant's DataSource.
 */
@Singleton
public class SourceFileRepository {

    private static final String COLUMNS = "source_id, path, size, modified, document_id, error";

    /** The source's files by path. */
    public Map<String, SourceFile> findBySource(DataSource dataSource, Long sourceId) {
        Map<String, SourceFile> files = new HashMap<>();
        for (SourceFile file : query(dataSource, "SELECT " + COLUMNS + " FROM source_file WHERE source_id = ?", sourceId)) {
            files.put(file.path(), file);
        }
        return files;
    }

    /** The source's files without a document - refused, or deleted by hand -, by path; at most limit. */
    public List<SourceFile> findWithoutDocument(DataSource dataSource, Long sourceId, int limit) {
        return query(dataSource, "SELECT " + COLUMNS + " FROM source_file WHERE source_id = ? AND document_id IS NULL "
                + "ORDER BY path LIMIT ?", sourceId, limit);
    }

    /** Records what became of the file - its document, or why there is none. */
    public void save(DataSource dataSource, SourceFile file) {
        String sql = "INSERT INTO source_file (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (source_id, path) "
                + "DO UPDATE SET size = EXCLUDED.size, modified = EXCLUDED.modified, document_id = EXCLUDED.document_id, "
                + "error = EXCLUDED.error";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, file.sourceId());
            statement.setString(2, file.path());
            statement.setLong(3, file.size());
            statement.setTimestamp(4, file.modified() == null ? null : Timestamp.from(file.modified()));
            statement.setObject(5, file.documentId(), Types.BIGINT);
            statement.setString(6, file.error());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record the source's file", e);
        }
    }

    public void delete(DataSource dataSource, Long sourceId, String path) {
        update(dataSource, "DELETE FROM source_file WHERE source_id = ? AND path = ?", sourceId, path);
    }

    /** Forgets everything the source saw - it was removed, or reads another store or folder from now on. */
    public void deleteBySource(DataSource dataSource, Long sourceId) {
        update(dataSource, "DELETE FROM source_file WHERE source_id = ?", sourceId);
    }

    private List<SourceFile> query(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            List<SourceFile> files = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    files.add(map(result));
                }
            }
            return files;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the source's files", e);
        }
    }

    private static void update(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not change the source's files", e);
        }
    }

    private static SourceFile map(ResultSet result) throws SQLException {
        Timestamp modified = result.getTimestamp("modified");
        return new SourceFile(result.getLong("source_id"), result.getString("path"), result.getLong("size"),
                modified == null ? null : modified.toInstant(), result.getObject("document_id", Long.class),
                result.getString("error"));
    }
}
