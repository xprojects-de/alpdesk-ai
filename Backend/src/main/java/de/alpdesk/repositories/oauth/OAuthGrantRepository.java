package de.alpdesk.repositories.oauth;

import de.alpdesk.entities.oauth.OAuthGrant;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface OAuthGrantRepository extends CrudRepository<OAuthGrant, Long> {

    Optional<OAuthGrant> findByConnectionId(Long connectionId);

    List<OAuthGrant> findByTenantId(Long tenantId);

    /** Under the row's lock: a refresh, a sign-in's start and its callback never overwrite one another. */
    @Query("SELECT * FROM oauth_grant WHERE connection_id = :connectionId FOR UPDATE")
    Optional<OAuthGrant> findByConnectionIdForUpdate(Long connectionId);

    Optional<OAuthGrant> findByState(String state);

    @Query("SELECT * FROM oauth_grant WHERE state = :state FOR UPDATE")
    Optional<OAuthGrant> findByStateForUpdate(String state);

    int deleteByConnectionId(Long connectionId);
}
