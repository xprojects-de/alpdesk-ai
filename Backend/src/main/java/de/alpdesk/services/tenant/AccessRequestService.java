package de.alpdesk.services.tenant;

import de.alpdesk.entities.tenant.AccessRequest;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.entities.tenant.TenantUser;
import de.alpdesk.repositories.tenant.AccessRequestRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Sign-in through the WAM proxy (docs/architecture/tenancy.md "Sign-in through a WAM proxy"): a known user who may log
 * in is signed in; anyone else waits. Someone unknown is asked for: the admin assigns the request to a tenant - which
 * makes a tenant user without a password, active or still waiting for the tenant admin - or rejects it (asked again on
 * the next visit).
 */
@Singleton
public class AccessRequestService {

    private final AccessRequestRepository requests;
    private final TenantUserService users;
    private final TenantRepository tenants;
    private final UserGroupService groups;

    public AccessRequestService(AccessRequestRepository requests, TenantUserService users, TenantRepository tenants,
                                UserGroupService groups) {
        this.requests = requests;
        this.users = users;
        this.tenants = tenants;
        this.groups = groups;
    }

    /**
     * What the sign-in through the proxy gave: the user signed in, or the tenants to choose from (the person has a
     * user who may log in in several), or neither - the person waits.
     */
    public record SignIn(@Nullable TenantUser user, List<TenantUserService.LoginAccount> choice) {

        static SignIn waiting() {
            return new SignIn(null, List.of());
        }

        static SignIn as(TenantUser user) {
            return new SignIn(user, List.of());
        }

        public boolean waits() {
            return user == null && choice.isEmpty();
        }
    }

    /**
     * Signs in the user the proxy sent (docs/architecture/tenancy.md "Sign-in through a WAM proxy"): someone with no
     * user waits (asked for now); with one user who may log in it is signed in; with several the person chooses - or
     * has chosen (tenantId: 404 for a tenant where it has no user who may log in - 409 user.noGroup only when it may
     * log in nowhere and lacks only a group there). A known person's request from
     * before (the admin created the user directly) goes. First, the IDM's groups (idmGroups, the proxy's groups
     * header) give the person a user in every active tenant with a group of such a WAM group, if it has none there
     * ({@link #accountsFromIdm}). 400 for a user name that cannot be a username. browserLanguage: the language of
     * a user it creates and of its request, unless the person chose one in a tenant already - ignored when it is no
     * language tag.
     */
    @Transactional
    public SignIn signIn(String proxyUser, @Nullable String displayName, @Nullable Long tenantId,
                         List<String> idmGroups, @Nullable String browserLanguage) {
        String username = TenantUserService.requireUsername(proxyUser);
        String language = users.languageOfPerson(username).orElse(TenantUserService.languageOrNull(browserLanguage));
        accountsFromIdm(username, displayName, idmGroups, language);
        recordIdmGroups(username, idmGroups);
        if (users.findAllByUsername(username).isEmpty()) {
            requests.insertIfAbsent(username, displayName == null ? username : displayName, Instant.now(), language);
            return SignIn.waiting();
        }
        requests.deleteByUsernameIgnoreCase(username);
        List<TenantUserService.LoginAccount> accounts = users.loginAccounts(username);
        if (tenantId != null) {
            TenantUser chosen = accounts.stream().map(TenantUserService.LoginAccount::user)
                    .filter(user -> user.tenantId().equals(tenantId)).findFirst().orElse(null);
            if (chosen == null) {
                // 404 while the person may log in elsewhere: the browser forgets a remembered tenant only on a 404
                // and signs in without it - a 409 there would lock the person out of its other tenants.
                if (accounts.isEmpty()) {
                    refuseWithoutGroup(username, tenantId);
                }
                throw ServiceException.notFound("wam.tenantNotFound", "You have no user in this tenant");
            }
            return signedIn(chosen);
        }
        if (accounts.isEmpty()) {
            refuseWithoutGroup(username, null);
        }
        return switch (accounts.size()) {
            case 0 -> SignIn.waiting();
            case 1 -> signedIn(accounts.getFirst().user());
            default -> new SignIn(null, accounts);
        };
    }

    /**
     * Membership from the IDM gives access (decided 5 November 2026, roadmap point 51): in every active tenant with a
     * group whose WAM group the person's IDM groups name, a user without one there gets it - active at once, no
     * password, its display name from the proxy, its IDM groups stored so the groups count from the first request. A
     * user that exists is not touched - only its membership follows the header. Nothing while the groups are not
     * checked (UserGroupService.tenantsOfWamGroups).
     */
    private void accountsFromIdm(String username, @Nullable String displayName, List<String> idmGroups,
                                 @Nullable String language) {
        for (Long tenantId : groups.tenantsOfWamGroups(idmGroups)) {
            boolean active = tenants.findById(tenantId).map(Tenant::active).orElse(false);
            if (!active || users.findInTenant(tenantId, username).isPresent()) {
                continue;
            }
            TenantUser created = users.createWithoutPassword(tenantId, username,
                    displayName == null ? username : displayName, true, false, language);
            groups.recordWamGroups(created.id(), idmGroups);
        }
    }

    /**
     * The IDM groups of this sign-in for every user of the person, before deciding who may get in: a group with a WAM
     * group counts only while the proxy lists it (as ActiveTenantUserRule keeps them on every later request).
     */
    private void recordIdmGroups(String username, List<String> idmGroups) {
        if (!groups.checksGroups()) {
            return;
        }
        for (TenantUser user : users.findAllByUsername(username)) {
            groups.recordWamGroups(user.id(), idmGroups);
        }
    }

    /** 409 user.noGroup for a person whose user would be let in but for a group - never told to wait for nothing. */
    private void refuseWithoutGroup(String username, @Nullable Long tenantId) {
        if (users.onlyLacksAGroup(username, tenantId)) {
            throw ServiceException.conflict("user.noGroup", TenantUserService.NO_GROUP_MESSAGE);
        }
    }

    private SignIn signedIn(TenantUser user) {
        users.recordLogin(user);
        return SignIn.as(user);
    }

    public List<AccessRequest> findAll() {
        return requests.findAllOrderByRequestedAt();
    }

    /**
     * Makes the request a user of the tenant, without a password: active at once (e.g. a tenant's first user, made its
     * admin too), or waiting for the tenant admin. Empty for an unknown request; 404 for an unknown tenant, 409 when
     * the username is taken meanwhile, 400 for an invalid language (null: the request's browser language, else English).
     */
    @Transactional
    public Optional<TenantUser> assign(Long requestId, Long tenantId, boolean active, boolean admin,
                                       @Nullable String language) {
        Optional<AccessRequest> request = requests.findById(requestId);
        if (request.isEmpty()) {
            return Optional.empty();
        }
        if (!tenants.existsById(tenantId)) {
            throw ServiceException.notFound("tenant.notFound", "Tenant not found");
        }
        TenantUser user = users.createWithoutPassword(tenantId, request.get().username(), request.get().displayName(),
                active, admin, language != null ? language : request.get().language());
        // The admin gives access with no group to choose: the tenant's "Standard" - the tenant admin may change it.
        groups.joinStandard(tenantId, user.id());
        requests.delete(request.get());
        return Optional.of(user);
    }

    @Transactional
    public boolean reject(Long requestId) {
        if (!requests.existsById(requestId)) {
            return false;
        }
        requests.deleteById(requestId);
        return true;
    }
}
