package de.alpdesk.repositories.document;

import de.alpdesk.entities.document.Document;
import de.alpdesk.entities.document.DocumentOrigin;
import de.alpdesk.entities.document.DocumentStatus;
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
 * The table "document" of a tenant database, by plain JDBC (Micronaut Data cannot reach databases created at runtime -
 * docs/architecture/tenancy.md). Every method takes the tenant's DataSource (services.tenant.TenantDatabases).
 */
@Singleton
public class DocumentRepository {

    private static final String COLUMNS = "id, file_name, folder, content_type, size_bytes, reads_images, status, error, "
            + "chunk_count, uploaded_by, origin, external_id, content_hash, area_id, source_id, created_at, updated_at";
    private static final String INSERT = "INSERT INTO document (file_name, folder, content_type, size_bytes, text, "
            + "reads_images, status, uploaded_by, origin, external_id, content_hash, area_id, source_id, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?) ";

    /** A new document, waiting to be embedded. */
    public Document insert(DataSource dataSource, NewDocument document) {
        String sql = INSERT + "RETURNING " + COLUMNS;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            setNewDocument(statement, document);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return map(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the document", e);
        }
    }

    /**
     * Stores a ZIP archive's file under its path in one statement, so two imports of the same path never make two
     * documents: a new path is inserted; a known one is replaced (waiting to be embedded again) - unless its text is
     * unchanged and its embedding did not fail, then nothing happens and empty is answered. The path is unique within
     * its area, so the area never changes here.
     */
    public Optional<Upserted> upsert(DataSource dataSource, NewDocument document) {
        return upsert(dataSource, document,
                "ON CONFLICT ((COALESCE(area_id, 0)), external_id) WHERE origin = 'UPLOAD' AND external_id IS NOT NULL ",
                "area_id = EXCLUDED.area_id", "", null);
    }

    /**
     * As {@link #upsert(DataSource, NewDocument)} for the client software's key, unique within the tenant. keepsArea:
     * a known document keeps its area (the push named none), else it moves to the new one. allowedAreaIds: only a known
     * document of the company knowledge or of these areas is replaced - checked in the same statement, so a document
     * moved into another area meanwhile is not taken out of it; null for any area. Empty for an unchanged document and
     * for one the key may not replace.
     */
    public Optional<Upserted> upsertPushed(DataSource dataSource, NewDocument document, boolean keepsArea,
                                           @Nullable List<Long> allowedAreaIds) {
        return upsert(dataSource, document,
                "ON CONFLICT (origin, external_id) WHERE origin <> 'UPLOAD' AND external_id IS NOT NULL ",
                keepsArea ? "area_id = document.area_id" : "area_id = EXCLUDED.area_id",
                allowedAreaIds == null ? "" : "AND (document.area_id IS NULL OR document.area_id = ANY(?)) ",
                allowedAreaIds);
    }

    private Optional<Upserted> upsert(DataSource dataSource, NewDocument document, String conflict, String area,
                                      String areaCondition, @Nullable List<Long> allowedAreaIds) {
        String sql = INSERT + conflict + "DO UPDATE SET "
                + "file_name = EXCLUDED.file_name, folder = EXCLUDED.folder, content_type = EXCLUDED.content_type, "
                + "size_bytes = EXCLUDED.size_bytes, text = EXCLUDED.text, reads_images = EXCLUDED.reads_images, "
                + "content_hash = EXCLUDED.content_hash, " + area + ", status = 'PENDING', error = NULL, "
                + "chunk_count = 0, updated_at = EXCLUDED.updated_at "
                + "WHERE (document.content_hash IS DISTINCT FROM EXCLUDED.content_hash OR document.status = 'FAILED') "
                + areaCondition
                // xmax is 0 for a row this statement inserted, set for one it updated (PostgreSQL).
                + "RETURNING " + COLUMNS + ", (xmax = 0) AS inserted";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            setNewDocument(statement, document);
            if (allowedAreaIds != null) {
                statement.setArray(15, connection.createArrayOf("bigint", allowedAreaIds.toArray()));
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new Upserted(map(result), result.getBoolean("inserted"))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the document", e);
        }
    }

    /**
     * Replaces a knowledge source's document in place with a new version of its file - same id, area and source, waiting
     * to be embedded again - unless its text is unchanged and its embedding did not fail. Empty when nothing was
     * replaced: unchanged, or deleted meanwhile (the caller tells the two apart).
     */
    public Optional<Document> replace(DataSource dataSource, Long id, NewDocument document) {
        String sql = "UPDATE document SET file_name = ?, folder = ?, content_type = ?, size_bytes = ?, text = ?, "
                + "reads_images = ?, content_hash = ?, status = 'PENDING', error = NULL, chunk_count = 0, updated_at = ? "
                + "WHERE id = ? AND (content_hash IS DISTINCT FROM ? OR status = 'FAILED') RETURNING " + COLUMNS;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, document.fileName());
            statement.setString(2, document.folder());
            statement.setString(3, document.contentType());
            statement.setLong(4, document.sizeBytes());
            statement.setString(5, document.text());
            statement.setBoolean(6, document.readsImages());
            statement.setString(7, document.contentHash());
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.setLong(9, id);
            statement.setString(10, document.contentHash());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the document", e);
        }
    }

    /** Moves a source's documents still in one area to another - one moved by hand elsewhere stays where it is. */
    public void moveSourceDocuments(DataSource dataSource, Long sourceId, @Nullable Long fromAreaId, @Nullable Long toAreaId) {
        update(dataSource, "UPDATE document SET area_id = ?, updated_at = ? WHERE source_id = ? "
                + "AND area_id IS NOT DISTINCT FROM ?", toAreaId, Timestamp.from(Instant.now()), sourceId, fromAreaId);
    }

    /** Deletes a source's documents; answers their ids. */
    public List<Long> deleteBySource(DataSource dataSource, Long sourceId) {
        return ids(dataSource, "DELETE FROM document WHERE source_id = ? RETURNING id", sourceId);
    }

    /** A removed source's documents stay as ordinary uploads, no longer kept up to date ("behalten"). */
    public void detachSource(DataSource dataSource, Long sourceId) {
        update(dataSource, "UPDATE document SET origin = 'UPLOAD', source_id = NULL, updated_at = ? WHERE source_id = ?",
                Timestamp.from(Instant.now()), sourceId);
    }

    /** The client software's document with this key. */
    public Optional<Document> findPushed(DataSource dataSource, String externalId) {
        return query(dataSource, "SELECT " + COLUMNS + " FROM document WHERE origin = 'API' AND external_id = ?",
                externalId).stream().findFirst();
    }

    /** The document a ZIP archive's file of this path became in this area (null: the company knowledge). */
    public Optional<Document> findImported(DataSource dataSource, @Nullable Long areaId, String path) {
        return query(dataSource, "SELECT " + COLUMNS + " FROM document WHERE origin = 'UPLOAD' AND external_id = ? "
                + "AND area_id IS NOT DISTINCT FROM ?", path, areaId).stream().findFirst();
    }

    /**
     * Another uploaded document with this content in the same area (its key not this one): the same file under another
     * path. In another area it is no duplicate - other people find it there.
     */
    public Optional<Document> findSameContent(DataSource dataSource, @Nullable Long areaId, String contentHash,
                                              String externalId) {
        return query(dataSource, "SELECT " + COLUMNS + " FROM document WHERE origin = 'UPLOAD' AND content_hash = ? "
                + "AND external_id IS DISTINCT FROM ? AND area_id IS NOT DISTINCT FROM ? ORDER BY id LIMIT 1",
                contentHash, externalId, areaId).stream().findFirst();
    }

    public int countInArea(DataSource dataSource, Long areaId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM document WHERE area_id = ?")) {
            statement.setLong(1, areaId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the documents", e);
        }
    }

    /** Moves the document to another area (null: the company knowledge) - its chunks stay, the search reads the area here. */
    public boolean updateArea(DataSource dataSource, Long id, @Nullable Long areaId) {
        return update(dataSource, "UPDATE document SET area_id = ?, updated_at = ? WHERE id = ?",
                areaId, Timestamp.from(Instant.now()), id) > 0;
    }

    /**
     * As {@link #updateArea}, only while the document is in the company knowledge or one of these areas - checked in the
     * same statement (the client software moving its own document). False when it is not (any more).
     */
    public boolean updateArea(DataSource dataSource, Long id, @Nullable Long areaId, List<Long> allowedAreaIds) {
        String sql = "UPDATE document SET area_id = ?, updated_at = ? WHERE id = ? AND (area_id IS NULL OR area_id = ANY(?))";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, areaId, Types.BIGINT);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, id);
            statement.setArray(4, connection.createArrayOf("bigint", allowedAreaIds.toArray()));
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not change the documents", e);
        }
    }

    /** Deletes the documents uploaded in the frontend into this area (null: the company knowledge); answers their ids. */
    public List<Long> deleteUploads(DataSource dataSource, @Nullable Long areaId) {
        return ids(dataSource, "DELETE FROM document WHERE origin = 'UPLOAD' AND area_id IS NOT DISTINCT FROM ? RETURNING id",
                areaId);
    }

    private static List<Long> ids(DataSource dataSource, String sql, @Nullable Long parameter) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter, Types.BIGINT);
            List<Long> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the documents", e);
        }
    }

    public List<Document> findAll(DataSource dataSource) {
        return query(dataSource, "SELECT " + COLUMNS + " FROM document ORDER BY created_at DESC, id DESC");
    }

    public Optional<Document> findById(DataSource dataSource, Long id) {
        return query(dataSource, "SELECT " + COLUMNS + " FROM document WHERE id = ?", id).stream().findFirst();
    }

    /** The ids of the documents waiting to be embedded, or interrupted while being embedded. */
    public List<Long> findUnfinishedIds(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return findUnfinishedIds(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the unfinished documents", e);
        }
    }

    /** As {@link #findUnfinishedIds(DataSource)} on a connection the caller closes. */
    public List<Long> findUnfinishedIds(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM document WHERE status IN ('PENDING', 'PROCESSING') ORDER BY id");
             ResultSet result = statement.executeQuery()) {
            List<Long> ids = new ArrayList<>();
            while (result.next()) {
                ids.add(result.getLong(1));
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the unfinished documents", e);
        }
    }

    /**
     * The text of the version with this content hash - empty once another replaced it: a run embeds the text of the
     * version it marks READY, never one pushed between its two reads.
     */
    public Optional<String> findText(DataSource dataSource, Long id, String contentHash) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT text FROM document WHERE id = ? AND content_hash = ?")) {
            statement.setLong(1, id);
            statement.setString(2, contentHash);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read document " + id, e);
        }
    }

    /**
     * The text of a READY document in the company knowledge or one of these areas (null: in any area) - what a search may
     * still find of it: empty when it was deleted, moved out of reach or is waiting to be embedded again meanwhile.
     */
    public Optional<String> findFoundText(DataSource dataSource, Long id, @Nullable List<Long> allowedAreaIds) {
        String sql = "SELECT text FROM document WHERE id = ? AND status = 'READY'"
                + (allowedAreaIds == null ? "" : " AND (area_id IS NULL OR area_id = ANY(?))");
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            if (allowedAreaIds != null) {
                statement.setArray(2, connection.createArrayOf("bigint", allowedAreaIds.toArray()));
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read document " + id, e);
        }
    }

    /**
     * The image model's reading replaces the text - the images are read, it is embedded like any other from now on.
     * Only while the document is still the one that was read (readHash): false when a new version replaced it meanwhile.
     */
    public boolean saveReading(DataSource dataSource, Long id, @Nullable String readHash, String text) {
        return update(dataSource, "UPDATE document SET text = ?, reads_images = FALSE, updated_at = ? "
                + "WHERE id = ? AND content_hash IS NOT DISTINCT FROM CAST(? AS TEXT)", text, Timestamp.from(Instant.now()), id, readHash) > 0;
    }

    /**
     * The document is embedded - only while it is still the version that was embedded (embeddedHash): one replaced
     * meanwhile stays waiting, so the previous version's chunks are found by no one until its own run (the search
     * reads only READY documents). False when it was replaced.
     */
    public boolean markReady(DataSource dataSource, Long id, @Nullable String embeddedHash, int chunkCount) {
        return update(dataSource, "UPDATE document SET status = 'READY', error = NULL, chunk_count = ?, updated_at = ? "
                + "WHERE id = ? AND content_hash IS NOT DISTINCT FROM CAST(? AS TEXT)", chunkCount,
                Timestamp.from(Instant.now()), id, embeddedHash) > 0;
    }

    public void updateStatus(DataSource dataSource, Long id, DocumentStatus status, String error, int chunkCount) {
        update(dataSource, "UPDATE document SET status = ?, error = ?, chunk_count = ?, updated_at = ? WHERE id = ?",
                status.name(), error, chunkCount, Timestamp.from(Instant.now()), id);
    }

    /** Every document waits to be embedded again (the embedding model changed). */
    public void markAllPending(DataSource dataSource) {
        update(dataSource, "UPDATE document SET status = 'PENDING', error = NULL, chunk_count = 0, updated_at = ?",
                Timestamp.from(Instant.now()));
    }

    public boolean delete(DataSource dataSource, Long id) {
        return update(dataSource, "DELETE FROM document WHERE id = ?", id) > 0;
    }

    /**
     * As {@link #delete}, only while the document is in the company knowledge or one of these areas - checked in the
     * same statement (the client software deleting): a document moved into another area meanwhile stays.
     */
    public boolean delete(DataSource dataSource, Long id, List<Long> allowedAreaIds) {
        String sql = "DELETE FROM document WHERE id = ? AND (area_id IS NULL OR area_id = ANY(?))";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setArray(2, connection.createArrayOf("bigint", allowedAreaIds.toArray()));
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the document", e);
        }
    }

    private List<Document> query(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            List<Document> documents = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    documents.add(map(result));
                }
            }
            return documents;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the documents", e);
        }
    }

    private int update(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not change the documents", e);
        }
    }

    private static void setNewDocument(PreparedStatement statement, NewDocument document) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        statement.setString(1, document.fileName());
        statement.setString(2, document.folder());
        statement.setString(3, document.contentType());
        statement.setLong(4, document.sizeBytes());
        statement.setString(5, document.text());
        statement.setBoolean(6, document.readsImages());
        statement.setObject(7, document.uploadedBy(), Types.BIGINT);
        statement.setString(8, document.origin().name());
        statement.setString(9, document.externalId());
        statement.setString(10, document.contentHash());
        statement.setObject(11, document.areaId(), Types.BIGINT);
        statement.setObject(12, document.sourceId(), Types.BIGINT);
        statement.setTimestamp(13, now);
        statement.setTimestamp(14, now);
    }

    private static Document map(ResultSet result) throws SQLException {
        return new Document(result.getLong("id"), result.getString("file_name"), result.getString("folder"),
                result.getString("content_type"), result.getLong("size_bytes"), result.getBoolean("reads_images"),
                DocumentStatus.valueOf(result.getString("status")), result.getString("error"),
                result.getInt("chunk_count"), result.getObject("uploaded_by", Long.class),
                DocumentOrigin.valueOf(result.getString("origin")), result.getString("external_id"),
                result.getString("content_hash"), result.getObject("area_id", Long.class),
                result.getObject("source_id", Long.class), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    /**
     * A document to store, with its extracted text; readsImages: the image model reads it before it is embedded;
     * areaId: null for the company knowledge; sourceId: the knowledge source it comes from, null for others.
     */
    public record NewDocument(String fileName, String folder, String contentType, long sizeBytes, String text,
                              boolean readsImages, Long uploadedBy, DocumentOrigin origin, String externalId,
                              String contentHash, @Nullable Long areaId, @Nullable Long sourceId) {
    }

    /** What an upsert stored; inserted false: an existing document was replaced. */
    public record Upserted(Document document, boolean inserted) {
    }
}
