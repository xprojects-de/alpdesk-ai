package de.alpdesk.repositories.ai;

import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.entities.ai.ProviderKey;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ProviderKeyRepository extends CrudRepository<ProviderKey, Long> {
    List<ProviderKey> findByTenantIdOrderByProvider(Long tenantId);

    Optional<ProviderKey> findByTenantIdAndProvider(Long tenantId, AiProvider provider);

    @Query("SELECT * FROM provider_key WHERE tenant_id IS NULL ORDER BY provider")
    List<ProviderKey> findGlobal();

    @Query("SELECT * FROM provider_key WHERE tenant_id IS NULL AND provider = :provider")
    Optional<ProviderKey> findGlobalByProvider(AiProvider provider);

    int deleteByTenantIdAndProvider(Long tenantId, AiProvider provider);

    @Query("DELETE FROM provider_key WHERE tenant_id IS NULL AND provider = :provider")
    int deleteGlobalByProvider(AiProvider provider);
}
