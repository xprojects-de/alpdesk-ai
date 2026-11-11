package de.alpdesk.repositories.branding;

import de.alpdesk.entities.branding.TenantBranding;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface TenantBrandingRepository extends CrudRepository<TenantBranding, Long> {
}
