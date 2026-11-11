package de.alpdesk.repositories.tenant;

import de.alpdesk.entities.tenant.TenantApiKey;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface TenantApiKeyRepository extends CrudRepository<TenantApiKey, Long> {
    List<TenantApiKey> findByTenantIdOrderByCreatedAt(Long tenantId);

    Optional<TenantApiKey> findByKeyHash(String keyHash);

    Optional<TenantApiKey> findByIdAndTenantId(Long id, Long tenantId);

    /** The key, locked until the transaction ends against another update. */
    @Query("SELECT * FROM tenant_api_key WHERE id = :id AND tenant_id = :tenantId FOR NO KEY UPDATE")
    Optional<TenantApiKey> findByIdAndTenantIdForNoKeyUpdate(Long id, Long tenantId);

    /** Deletes it; 0 when another delete removed it meanwhile (the row lock waited for it). */
    int deleteByIdAndTenantId(Long id, Long tenantId);

    void updateLastUsedAt(@Id Long id, Instant lastUsedAt);
}
