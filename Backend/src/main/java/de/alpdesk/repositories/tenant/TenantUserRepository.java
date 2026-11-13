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
     * Counts an attempt to log in with a password, before the password is checked - 0 when the account is locked and
     * takes none. The attempt that reaches maxAttempts locks the account until lockedUntil and starts the count anew.
     * In one statement and before the check: attempts sent at once are each counted, so no more than maxAttempts
     * passwords are ever tried - a count after the check would let all of them through first.
     */
    @Query("""
            UPDATE tenant_user SET
              failed_logins = CASE WHEN failed_logins + 1 >= :maxAttempts THEN 0 ELSE failed_logins + 1 END,
              locked_until = CASE WHEN failed_logins + 1 >= :maxAttempts THEN CAST(:lockedUntil AS TIMESTAMPTZ) ELSE NULL END
            WHERE id = :id AND (locked_until IS NULL OR locked_until <= :now)""")
    int countLoginAttempt(Long id, int maxAttempts, Instant now, Instant lockedUntil);

    /** The right password, or one an admin set: the count of attempts starts anew and a lock ends. */
    @Query("UPDATE tenant_user SET failed_logins = 0, locked_until = NULL WHERE id = :id")
    void clearLoginAttempts(Long id);

    /** The person alone - set when the admin adds the person's user to another tenant. */
    void updatePerson(@Id Long id, Long person);

    /**
     * The language alone: a whole row written back would undo what an admin changed meanwhile on a user the person has
     * in another tenant (deactivated, demoted, renamed).
     */
    @Query("UPDATE tenant_user SET language = :language, updated_at = :updatedAt WHERE id = :id")
    void updateLanguage(Long id, String language, Instant updatedAt);
}
