package de.alpdesk.repositories.mcp;

import de.alpdesk.entities.mcp.McpTool;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface McpToolRepository extends CrudRepository<McpTool, Long> {
    List<McpTool> findByConnectionIdOrderByName(Long connectionId);

    Optional<McpTool> findByIdAndConnectionId(Long id, Long connectionId);

    void deleteByConnectionId(Long connectionId);
}
