package de.alpdesk.repositories.tenant;

import de.alpdesk.entities.tenant.AccessRequest;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface AccessRequestRepository extends CrudRepository<AccessRequest, Long> {

    List<AccessRequest> findAllOrderByRequestedAt();

    /** Asks for access once per username - a second tab signing in at the same moment adds nothing. */
    @Query("""
            INSERT INTO access_request (username, display_name, requested_at, language)
            VALUES (:username, :displayName, :requestedAt, :language)
            ON CONFLICT ((LOWER(username))) DO NOTHING""")
    void insertIfAbsent(String username, String displayName, Instant requestedAt, @Nullable String language);

    void deleteByUsernameIgnoreCase(String username);
}
