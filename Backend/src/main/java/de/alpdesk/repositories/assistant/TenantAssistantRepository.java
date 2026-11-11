package de.alpdesk.repositories.assistant;

import de.alpdesk.entities.assistant.TenantAssistant;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface TenantAssistantRepository extends CrudRepository<TenantAssistant, Long> {
}
