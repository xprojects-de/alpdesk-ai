package de.alpdesk.repositories.docsite;

import de.alpdesk.entities.docsite.DocSite;
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
 * The table "doc_site" of a tenant database, by plain JDBC (Micronaut Data cannot reach databases created at runtime -
 * docs/architecture/tenancy.md). Every method takes the tenant's DataSource (services.tenant.TenantDatabases).
 */
@Singleton
public class DocSiteRepository {

    private static final String COLUMNS = "id, area_id, storage_prefix, file_count, size_bytes, uploaded_at";

    /** Every website of the tenant, the whole tenant's first. */
    public List<DocSite> findAll(DataSource dataSource) {
        String sql = "SELECT " + COLUMNS + " FROM doc_site ORDER BY area_id NULLS FIRST";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<DocSite> sites = new ArrayList<>();
            while (result.next()) {
                sites.add(map(result));
            }
            return sites;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the websites", e);
        }
    }

    public Optional<DocSite> findById(DataSource dataSource, Long id) {
        return findOne(dataSource, "SELECT " + COLUMNS + " FROM doc_site WHERE id = ?", id);
    }

    /** The website of the area (null: the whole tenant's). */
    public Optional<DocSite> findByArea(DataSource dataSource, @Nullable Long areaId) {
        return findOne(dataSource, "SELECT " + COLUMNS + " FROM doc_site WHERE COALESCE(area_id, 0) = ?",
                areaId == null ? 0L : areaId);
    }

    /**
     * Stores the scope's website - a new one, or the known one pointed at its new files - and answers the stored row and
     * the storage prefix it replaced (null for a new one). The known row is locked while it is read, so of two uploads
     * at once each one deletes exactly the files the other replaced. (Two first uploads of a scope at the same moment
     * leave the files of one behind, unreached - not worth a lock of its own.)
     */
    public Replaced replace(DataSource dataSource, @Nullable Long areaId, String storagePrefix, int fileCount,
                            long sizeBytes, Instant uploadedAt) {
        String select = "SELECT storage_prefix FROM doc_site WHERE COALESCE(area_id, 0) = ? FOR UPDATE";
        String upsert = "INSERT INTO doc_site (area_id, storage_prefix, file_count, size_bytes, uploaded_at) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT ((COALESCE(area_id, 0))) DO UPDATE SET storage_prefix = EXCLUDED.storage_prefix, "
                + "file_count = EXCLUDED.file_count, size_bytes = EXCLUDED.size_bytes, uploaded_at = EXCLUDED.uploaded_at "
                + "RETURNING " + COLUMNS;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String replaced = replacedPrefix(connection, select, areaId);
                DocSite site;
                try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                    if (areaId == null) {
                        statement.setNull(1, Types.BIGINT);
                    } else {
                        statement.setLong(1, areaId);
                    }
                    statement.setString(2, storagePrefix);
                    statement.setInt(3, fileCount);
                    statement.setLong(4, sizeBytes);
                    statement.setTimestamp(5, Timestamp.from(uploadedAt));
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        site = map(result);
                    }
                }
                connection.commit();
                return new Replaced(site, replaced);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the website", e);
        }
    }

    private static @Nullable String replacedPrefix(Connection connection, String select, @Nullable Long areaId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setLong(1, areaId == null ? 0L : areaId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    /** Deletes the row and answers its storage prefix; empty when there was none. */
    public Optional<String> delete(DataSource dataSource, Long id) {
        String sql = "DELETE FROM doc_site WHERE id = ? RETURNING storage_prefix";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the website", e);
        }
    }

    private Optional<DocSite> findOne(DataSource dataSource, String sql, Long value) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the website", e);
        }
    }

    private static DocSite map(ResultSet result) throws SQLException {
        Long areaId = result.getObject("area_id", Long.class);
        return new DocSite(result.getLong("id"), areaId, result.getString("storage_prefix"),
                result.getInt("file_count"), result.getLong("size_bytes"),
                result.getTimestamp("uploaded_at").toInstant());
    }

    /** The stored website and the storage prefix of the files it replaced (null: none). */
    public record Replaced(DocSite site, @Nullable String replacedPrefix) {
    }
}
