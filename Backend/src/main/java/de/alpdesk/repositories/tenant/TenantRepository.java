package de.alpdesk.repositories.tenant;

import de.alpdesk.entities.tenant.SearchLanguage;
import de.alpdesk.entities.tenant.Tenant;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface TenantRepository extends CrudRepository<Tenant, Long> {
    List<Tenant> findAllOrderByName();

    Optional<Tenant> findByNameIgnoreCase(String name);

    /** The tenants with a sandbox of their own - no instance may be entered at two of them. */
    List<Tenant> findBySandboxUrlsIsNotNull();

    void updateEmbeddingModelId(@Id Long id, Long embeddingModelId);

    void updateRerankingModelId(@Id Long id, @Nullable Long rerankingModelId);

    void updateSearchLanguage(@Id Long id, SearchLanguage searchLanguage);

    Optional<Tenant> findByLoginKey(String loginKey);

    void updateLoginKey(@Id Long id, String loginKey);

    /** Deletes it; 0 when another delete removed it meanwhile (the row lock waited for it). */
    @Query("DELETE FROM tenant WHERE id = :id")
    int deleteCountingRows(Long id);

    /**
     * The tenant, locked until the transaction ends against another update (FOR NO KEY UPDATE: foreign keys to it -
     * everything the tenant owns - do not wait).
     */
    @Query("SELECT * FROM tenant WHERE id = :id FOR NO KEY UPDATE")
    Optional<Tenant> findByIdForNoKeyUpdate(Long id);

    /** The tenant, locked until the transaction ends also against new rows pointing to it (their foreign key waits). */
    @Query("SELECT * FROM tenant WHERE id = :id FOR UPDATE")
    Optional<Tenant> findByIdForUpdate(Long id);
}
