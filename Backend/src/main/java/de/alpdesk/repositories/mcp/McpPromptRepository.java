package de.alpdesk.repositories.mcp;

import de.alpdesk.entities.mcp.McpPrompt;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface McpPromptRepository extends CrudRepository<McpPrompt, Long> {
    List<McpPrompt> findByConnectionIdOrderByName(Long connectionId);

    Optional<McpPrompt> findByIdAndConnectionId(Long id, Long connectionId);

    void deleteByConnectionId(Long connectionId);
}
