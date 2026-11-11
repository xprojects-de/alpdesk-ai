package de.alpdesk.repositories.assistant;

import de.alpdesk.entities.assistant.ChatTemplate;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ChatTemplateRepository extends CrudRepository<ChatTemplate, Long> {
    List<ChatTemplate> findByTenantIdOrderByName(Long tenantId);

    Optional<ChatTemplate> findByIdAndTenantId(Long id, Long tenantId);

    int deleteByIdAndTenantId(Long id, Long tenantId);
}
