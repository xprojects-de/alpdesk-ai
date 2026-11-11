package de.alpdesk.dto.login;

import de.alpdesk.services.tenant.TenantUserService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A tenant where the person has a user who may log in - one of the tenants it chooses among at the sign-in through
 * the WAM proxy or in the user menu's switch. loginKey: the tenant's login page (docs/architecture/branding.md), whose
 * logo the choice shows - only with WAM, where the proxy's header proves it is the same person; without WAM the same
 * username may be another person's, who must not learn the key that keeps a company's use of the installation hidden.
 */
@Serdeable
public record TenantChoice(Long id, String name, @Nullable String loginKey) {

    public static TenantChoice from(TenantUserService.LoginAccount account, boolean withLoginKey) {
        return new TenantChoice(account.tenant().id(), account.tenant().name(),
                withLoginKey ? account.tenant().loginKey() : null);
    }
}
