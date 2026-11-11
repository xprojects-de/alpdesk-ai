package de.alpdesk.repositories.filestore;

import de.alpdesk.entities.filestore.FileStore;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface FileStoreRepository extends CrudRepository<FileStore, Long> {
    List<FileStore> findByTenantIdOrderByName(Long tenantId);

    Optional<FileStore> findByIdAndTenantId(Long id, Long tenantId);

    int deleteByIdAndTenantId(Long id, Long tenantId);

    /**
     * A check's outcome only, and only for the store as it was checked: one changed meanwhile (another server, a new
     * fingerprint) keeps its own state - the outcome belonged to what it was before.
     */
    @Query("UPDATE file_store SET checked_at = :checkedAt, check_error = :checkError WHERE id = :id AND updated_at = :updatedAt")
    int updateCheck(Long id, Instant updatedAt, Instant checkedAt, @Nullable String checkError);
}
