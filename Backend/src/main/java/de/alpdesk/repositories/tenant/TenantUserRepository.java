package de.alpdesk.repositories.tenant;

import de.alpdesk.entities.tenant.TenantUser;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface TenantUserRepository extends CrudRepository<TenantUser, Long> {
    List<TenantUser> findByTenantIdOrderByUsername(Long tenantId);

    Optional<TenantUser> findByIdAndTenantId(Long id, Long tenantId);

    /** The users of this username in every tenant - one person may have a user in several (decided 5 November 2026). */
    List<TenantUser> findAllByUsernameIgnoreCaseOrderByTenantId(String username);

    Optional<TenantUser> findByTenantIdAndUsernameIgnoreCase(Long tenantId, String username);

    /** The user, locked until the transaction ends against another update. */
    @Query("SELECT * FROM tenant_user WHERE id = :id AND tenant_id = :tenantId FOR NO KEY UPDATE")
    Optional<TenantUser> findByIdAndTenantIdForNoKeyUpdate(Long id, Long tenantId);

    /**
     * Whether the user is a tenant admin - empty when it may not act any more: it or its tenant deleted or deactivated,
     * or the token older than a password an admin set. Asked on every request of a tenant user
     * (security.ActiveTenantUserRule), so a deactivated user's JWT stops working at once - one query, the admin flag
     * decides whether a group is needed (TenantUserService.isActive).
     */
    @Query("""
            SELECT u.admin FROM tenant_user u JOIN tenant t ON t.id = u.tenant_id
            WHERE u.id = :id AND u.tenant_id = :tenantId AND u.active AND t.active
              AND (u.sessions_valid_from IS NULL OR u.sessions_valid_from <= :issuedAt)""")
    Optional<Boolean> findAdminIfActive(Long id, Long tenantId, Instant issuedAt);

    /** Deletes it; 0 when another delete removed it meanwhile. */
    int deleteByIdAndTenantId(Long id, Long tenantId);

    void updateLastLoginAt(@Id Long id, Instant lastLoginAt);

    /**
     * The language alone: a whole row written back would undo what an admin changed meanwhile on a user the person has
     * in another tenant (deactivated, demoted, renamed).
     */
    @Query("UPDATE tenant_user SET language = :language, updated_at = :updatedAt WHERE id = :id")
    void updateLanguage(Long id, String language, Instant updatedAt);
}
