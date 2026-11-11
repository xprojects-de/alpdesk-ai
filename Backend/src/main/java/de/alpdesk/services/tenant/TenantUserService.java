package de.alpdesk.services.tenant;

import de.alpdesk.dto.tenant.TenantUserRequest;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.entities.chat.ConversationOwner;
import de.alpdesk.entities.tenant.TenantUser;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.repositories.tenant.TenantUserRepository;
import de.alpdesk.security.PasswordHasher;
import de.alpdesk.security.WamProxy;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A tenant's users (its employees in the frontend): managed by the admin and the tenant's admins, logging in
 * themselves. A tenant admin cannot take its own admin rights away, deactivate or delete itself - the tenant would be
 * left without the one who just acted (docs/architecture/tenancy.md "Who does what"). With the WAM proxy on, users have
 * no password and sign in through the proxy only.
 */
@Singleton
public class TenantUserService {

    public static final int MIN_PASSWORD_LENGTH = 12;
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._@-]{3,100}$");
    /**
     * A language tag such as "en", "de" or "pt-BR". The backend keeps no list of languages - the frontend offers them
     * and falls back to English for one it no longer has (docs/architecture/i18n.md).
     */
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
    private static final int MAX_LANGUAGE_LENGTH = 20;
    public static final String DEFAULT_LANGUAGE = "en";
    /**
     * Told a user who would be let in but for a group (access needs a group), with the code "user.noGroup" - at the
     * sign-in with or without WAM; the code stays a literal at each throw (ErrorCodeSpec reads them).
     */
    public static final String NO_GROUP_MESSAGE = "You are in no group of this company - ask its administrator to add "
            + "you to one";
    private static final String WAM_USERS_BY_ADMIN = "Users sign in through the WAM proxy - new ones come through their "
            + "access request, and only the admin creates users or changes a username";

    private static final Logger LOG = LoggerFactory.getLogger(TenantUserService.class);

    private final TenantUserRepository repository;
    private final TenantRepository tenantRepository;
    private final ChatService chats;
    private final TransactionOperations<Connection> transactions;
    private final String adminUsername;
    private final WamProxy wam;
    private final UserGroupService groups;

    public TenantUserService(TenantUserRepository repository, TenantRepository tenantRepository, ChatService chats,
                             TransactionOperations<Connection> transactions,
                             @Value("${app.username}") String adminUsername, WamProxy wam, UserGroupService groups) {
        this.repository = repository;
        this.tenantRepository = tenantRepository;
        this.chats = chats;
        this.transactions = transactions;
        this.adminUsername = adminUsername;
        this.wam = wam;
        this.groups = groups;
    }

    public List<TenantUser> findByTenant(Long tenantId) {
        return repository.findByTenantIdOrderByUsername(tenantId);
    }

    public Optional<TenantUser> findById(Long tenantId, Long userId) {
        return repository.findByIdAndTenantId(userId, tenantId);
    }

    /** A user who may log in, with its tenant - one of the tenants a person chooses among (sign-in, switch). */
    public record LoginAccount(TenantUser user, Tenant tenant) {
    }

    /**
     * 400 for an invalid username, name or password, 409 if the username is taken (in this tenant, or by the admin). With
     * the WAM proxy on, a user has no password - sending one is 400 -, and only the admin creates users (actingUserId:
     * the tenant admin who asks, null for the admin; 409 for a tenant admin): a username is then a person's sign-in at
     * the proxy, and a tenant admin could take a person meant for another tenant - who comes through the access
     * request the admin assigns.
     */
    @Transactional
    public TenantUser create(Long tenantId, TenantUserRequest request, @Nullable Long actingUserId) {
        if (wam.enabled() && actingUserId != null) {
            throw ServiceException.conflict("user.wamUsersByAdmin", WAM_USERS_BY_ADMIN);
        }
        String username = requireUsername(request);
        String displayName = requireDisplayName(request);
        String language = request.language() == null ? DEFAULT_LANGUAGE : requireLanguage(request.language());
        refusePasswordWithWam(request.password());
        if (!wam.enabled() && request.password() == null) {
            throw ServiceException.badRequest("user.passwordRequired", "Password is required");
        }
        assertUsernameAvailable(tenantId, username, null);
        Instant now = Instant.now();
        TenantUser user = repository.save(new TenantUser(null, tenantId, username, displayName,
                request.password() == null ? null : hash(request.password()),
                request.active() == null || request.active(), Boolean.TRUE.equals(request.admin()), now, now, null, null,
                language));
        if (request.groupIds() == null) {
            // Left out: the tenant's "Standard" - without a group the new user would not get in.
            groups.joinStandard(tenantId, user.id());
        } else {
            groups.assignUser(tenantId, user.id(), request.groupIds());
        }
        return user;
    }

    /**
     * A user without a password, for the WAM proxy's sign-in (an access request the admin assigned): it signs in with
     * this username at the proxy. 409 if the username is taken in the tenant; 400 for an invalid language (null:
     * English).
     */
    @Transactional
    public TenantUser createWithoutPassword(Long tenantId, String username, String displayName, boolean active,
                                            boolean admin, @Nullable String language) {
        String checkedLanguage = language == null ? DEFAULT_LANGUAGE : requireLanguage(language);
        assertUsernameAvailable(tenantId, username, null);
        Instant now = Instant.now();
        return repository.save(new TenantUser(null, tenantId, username, displayName, null, active, admin, now, now,
                null, null, checkedLanguage));
    }

    /**
     * Left-out password, active, admin, language and groups stay as they are. A new password ends the user's sessions: whoever had them
     * - e.g. someone who misused the account - must log in with it. So does a new username: with WAM the proxy still
     * sends the old person's name, which matches the token of the old name - the account went to someone else. 400 / 409
     * as for {@link #create}; 409 when a tenant
     * admin (actingUserId - null for the admin) would deactivate itself or take its own admin rights away.
     */
    @Transactional
    public Optional<TenantUser> update(Long tenantId, Long userId, TenantUserRequest request,
                                       @Nullable Long actingUserId) {
        Optional<TenantUser> existing = repository.findByIdAndTenantIdForNoKeyUpdate(userId, tenantId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (userId.equals(actingUserId) && (Boolean.FALSE.equals(request.active()) || Boolean.FALSE.equals(request.admin()))) {
            throw ServiceException.conflict("user.cannotDemoteSelf",
                    "You cannot deactivate yourself or take away your own admin rights");
        }
        String username = requireUsername(request);
        String displayName = requireDisplayName(request);
        String language = request.language() == null ? null : requireLanguage(request.language());
        TenantUser user = existing.get();
        if (wam.enabled() && actingUserId != null && !username.equalsIgnoreCase(user.username())) {
            // Renamed to another person's sign-in, that person would sign in as this one - with its conversations.
            throw ServiceException.conflict("user.wamUsersByAdmin", WAM_USERS_BY_ADMIN);
        }
        refusePasswordWithWam(request.password());
        assertUsernameAvailable(tenantId, username, userId);
        groups.assignUser(tenantId, userId, request.groupIds());
        boolean endSessions = request.password() != null || !username.equalsIgnoreCase(user.username());
        return Optional.of(repository.update(new TenantUser(user.id(), tenantId, username, displayName,
                request.password() != null ? hash(request.password()) : user.passwordHash(),
                request.active() != null ? request.active() : user.active(),
                request.admin() != null ? request.admin() : user.admin(),
                user.createdAt(), Instant.now(), user.lastLoginAt(),
                endSessions ? Instant.now().truncatedTo(ChronoUnit.SECONDS) : user.sessionsValidFrom(),
                language != null ? language : user.language())));
    }

    /**
     * Deletes the user - its JWT stops working at once (security.ActiveTenantUserRule) - and, after the commit, its
     * conversations with their files: nobody could open them any more. What cannot be removed stays behind unreachable
     * (user ids are never given out again); the deletion itself stands. 409 when a tenant admin (actingUserId - null
     * for the admin) would delete itself.
     */
    public boolean delete(Long tenantId, Long userId, @Nullable Long actingUserId) {
        if (userId.equals(actingUserId)) {
            throw ServiceException.conflict("user.cannotDeleteSelf", "You cannot delete yourself");
        }
        if (!transactions.executeWrite(_ -> repository.deleteByIdAndTenantId(userId, tenantId) > 0)) {
            return false;
        }
        try {
            chats.deleteAllOf(tenantId, ConversationOwner.user(userId));
        } catch (RuntimeException e) {
            LOG.warn("Conversations of deleted user {} of tenant {} could not be removed: {}", userId, tenantId, e.toString());
        }
        return true;
    }

    /**
     * The active user of an active tenant with this username and password, or empty. A username in several tenants
     * needs the tenant's login key (the header X-Login-Key the tenant's login page sends) - without it, or with a
     * key nobody has, it is refused like a wrong password. Takes as long for an unknown username as for a wrong
     * password, so the answer does not tell which usernames exist. Always empty with the WAM proxy on: users sign in
     * through it. No transaction around it: the password check takes some hundred milliseconds of PBKDF2, and logins
     * at a shift's start would hold the pool's connections meanwhile - only the last login is written, on its own.
     * A right password of a user who only lacks a group is told so (409 {@code user.noGroup}, as at the WAM sign-in):
     * after the password, it gives nothing away, and "wrong password" would send the tenant admin to the wrong place.
     */
    public Optional<TenantUser> authenticate(String username, String password, @Nullable String loginKey) {
        Optional<TenantUser> user = username == null || wam.enabled() ? Optional.empty()
                : userToLogIn(username.trim(), loginKey);
        // A user without a password (signed in through WAM before, added from another tenant) takes as long as an
        // unknown name: the time of the answer must not tell that the name exists.
        if (user.isEmpty() || user.get().passwordHash() == null) {
            PasswordHasher.matchNothing(password);
            return Optional.empty();
        }
        if (!PasswordHasher.matches(password, user.get().passwordHash())) {
            return Optional.empty();
        }
        if (!mayLogIn(user.get())) {
            if (onlyLacksAGroup(user.get().username(), user.get().tenantId())) {
                throw ServiceException.conflict("user.noGroup", NO_GROUP_MESSAGE);
            }
            return Optional.empty();
        }
        writeLastLogin(user.get());
        return user;
    }

    private Optional<TenantUser> userToLogIn(String username, @Nullable String loginKey) {
        if (loginKey != null && !loginKey.isBlank()) {
            return tenantRepository.findByLoginKey(loginKey.trim())
                    .flatMap(tenant -> repository.findByTenantIdAndUsernameIgnoreCase(tenant.id(), username));
        }
        // In several tenants the name alone does not tell which user signs in - the tenant's own login page does.
        List<TenantUser> users = repository.findAllByUsernameIgnoreCaseOrderByTenantId(username);
        return users.size() == 1 ? Optional.of(users.getFirst()) : Optional.empty();
    }

    /** The user of this username (case does not matter) in the tenant, whatever its state. */
    public Optional<TenantUser> findInTenant(Long tenantId, String username) {
        return repository.findByTenantIdAndUsernameIgnoreCase(tenantId, username);
    }

    /** The users of this username (case does not matter) in every tenant, whatever their state. */
    public List<TenantUser> findAllByUsername(String username) {
        return repository.findAllByUsernameIgnoreCaseOrderByTenantId(username);
    }

    /**
     * Whether the person has a user who would be let in but for a group: active, of an active tenant, no tenant admin,
     * in no group that counts - it is told so at the sign-in rather than told to wait (tenantId: in that tenant only).
     */
    public boolean onlyLacksAGroup(String username, @Nullable Long tenantId) {
        for (TenantUser user : findAllByUsername(username)) {
            boolean inTenant = tenantId == null || user.tenantId().equals(tenantId);
            boolean otherwiseIn = user.active() && !user.admin()
                    && tenantRepository.findById(user.tenantId()).map(Tenant::active).orElse(false);
            if (inTenant && otherwiseIn && !inAGroup(user.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The users of this username who may log in, with their tenants, by the tenant's name - tenant admins and users in
     * a group ({@link #inAGroup}). Without the WAM proxy only those with a password: nothing else could be switched to,
     * and a user without one is not yet the person's - the admin has not handed it over.
     */
    public List<LoginAccount> loginAccounts(String username) {
        List<LoginAccount> accounts = new ArrayList<>();
        for (TenantUser user : findAllByUsername(username)) {
            if (!user.active() || (!wam.enabled() && user.passwordHash() == null)
                    || !(user.admin() || inAGroup(user.id()))) {
                continue;
            }
            tenantRepository.findById(user.tenantId()).filter(Tenant::active)
                    .ifPresent(tenant -> accounts.add(new LoginAccount(user, tenant)));
        }
        accounts.sort(Comparator.comparing(account -> account.tenant().name().toLowerCase()));
        return accounts;
    }

    /**
     * The switch in the user menu: the user of the same username in another tenant, who must be allowed to log in
     * (404 for a tenant where there is none). With the WAM proxy on, its header proves the person (checked on every
     * request by security.ActiveTenantUserRule); without it a username proves no person - the admin may give the same
     * name to two people in two tenants -, so the target account's password is asked like at a login (400 without
     * one or with a wrong one: a 401 would log the person out of the account it is in). No transaction around the
     * password check, as at {@link #authenticate}.
     */
    public TenantUser switchTenant(Long tenantId, Long userId, Long targetTenantId, @Nullable String password) {
        TenantUser current = repository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("user.notFound", "User not found"));
        TenantUser target = targetTenantId == null ? null : repository
                .findByTenantIdAndUsernameIgnoreCase(targetTenantId, current.username())
                .filter(this::mayLogIn).orElse(null);
        if (target == null) {
            throw ServiceException.notFound("account.switchTenantNotFound", "You have no user in this tenant");
        }
        if (!wam.enabled()) {
            if (password == null || password.isEmpty()) {
                throw ServiceException.badRequest("account.passwordRequired",
                        "The password of your user in that tenant is required");
            }
            if (!PasswordHasher.matches(password, target.passwordHash())) {
                throw ServiceException.badRequest("account.switchPasswordWrong",
                        "The password of your user in that tenant is wrong");
            }
        }
        writeLastLogin(target);
        return target;
    }

    /**
     * The admin adds a person's user of one tenant to another: the same username, display name and language, no
     * password (with the WAM proxy the person signs in through it; without, the admin sets one), active or waiting
     * for the tenant admin, tenant admin or not - in the tenant's group "Standard". Empty for an unknown user; 409 when
     * the name is taken in the tenant.
     */
    @Transactional
    public Optional<TenantUser> addFromOtherTenant(Long tenantId, Long sourceUserId, boolean active, boolean admin) {
        return repository.findById(sourceUserId).map(source -> {
            TenantUser added = createWithoutPassword(tenantId, source.username(), source.displayName(), active, admin,
                    source.language());
            groups.joinStandard(tenantId, added.id());
            return added;
        });
    }

    private void writeLastLogin(TenantUser user) {
        transactions.executeWrite(_ -> {
            repository.updateLastLoginAt(user.id(), Instant.now());
            return null;
        });
    }

    /** The user signed in without a password (the WAM proxy) - recorded like a login with one. */
    @Transactional
    public void recordLogin(TenantUser user) {
        repository.updateLastLoginAt(user.id(), Instant.now());
    }

    /**
     * Whether a logged-in user may still act with a token issued at issuedAt - and whether it is a tenant admin; empty
     * when it may not: deactivated, deleted, its tenant deactivated, the token older than a password an admin set.
     * Asked on every request of a tenant user (security.ActiveTenantUserRule), which then needs a group of a user who
     * is no tenant admin ({@link #inAGroup}).
     */
    public Optional<Boolean> adminIfActive(Long tenantId, Long userId, Instant issuedAt) {
        return repository.findAdminIfActive(userId, tenantId, issuedAt);
    }

    /**
     * Access needs a group (decided 7 November 2026): a tenant user who is no tenant admin gets in only while one of
     * its groups counts for it - a group without a WAM group while it is a member, one with a WAM group while the
     * proxy lists that IDM group (UserGroupService). Taken out of its last group - by a tenant admin or in the IDM -
     * it keeps its account and conversations and gets in again once it is in one.
     */
    public boolean inAGroup(Long userId) {
        return !groups.groupsOfUser(userId).isEmpty();
    }

    /**
     * The user's own password change: 400 if the current password is wrong or the new one too short, 409 with the WAM
     * proxy on - there is no password.
     */
    @Transactional
    public void changePassword(Long tenantId, Long userId, String currentPassword, String newPassword) {
        if (wam.enabled()) {
            throw ServiceException.conflict("account.wamNoPassword",
                    "You sign in through the WAM proxy and have no password");
        }
        TenantUser user = repository.findByIdAndTenantIdForNoKeyUpdate(userId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("user.notFound", "User not found"));
        if (!PasswordHasher.matches(currentPassword, user.passwordHash())) {
            throw ServiceException.badRequest("account.currentPasswordWrong", "The current password is wrong");
        }
        repository.update(new TenantUser(user.id(), user.tenantId(), user.username(), user.displayName(),
                hash(newPassword), user.active(), user.admin(), user.createdAt(), Instant.now(), user.lastLoginAt(),
                user.sessionsValidFrom(), user.language()));
    }

    /**
     * The user's own choice of the frontend's language. With the WAM proxy for every user of the person (the same
     * username in other tenants - the proxy's header proves the person), so a switch of tenant keeps it; without it
     * for this user only: a username proves no person there, and the same name in another tenant may be someone else's
     * ({@link #switchTenant}). 400 for a tag of the wrong form.
     */
    @Transactional
    public void changeLanguage(Long tenantId, Long userId, @Nullable String language) {
        String checked = requireLanguage(language);
        TenantUser user = repository.findByIdAndTenantIdForNoKeyUpdate(userId, tenantId)
                .orElseThrow(() -> ServiceException.notFound("user.notFound", "User not found"));
        List<TenantUser> accounts = wam.enabled()
                ? repository.findAllByUsernameIgnoreCaseOrderByTenantId(user.username()) : List.of(user);
        Instant now = Instant.now();
        for (TenantUser account : accounts) {
            repository.updateLanguage(account.id(), checked, now);
        }
    }

    /** The language the person speaks in a tenant already - empty for someone with no user yet. */
    public Optional<String> languageOfPerson(String username) {
        return repository.findAllByUsernameIgnoreCaseOrderByTenantId(username).stream().map(TenantUser::language)
                .findFirst();
    }

    /** The tag, or null when it is none - for a browser's language, which may be anything. */
    public static @Nullable String languageOrNull(@Nullable String language) {
        String trimmed = language == null ? "" : language.trim();
        return trimmed.length() <= MAX_LANGUAGE_LENGTH && LANGUAGE.matcher(trimmed).matches() ? trimmed : null;
    }

    /** An active user of an active tenant - a tenant admin, or one in a group ({@link #inAGroup}). */
    public boolean mayLogIn(TenantUser user) {
        return user.active() && tenantRepository.findById(user.tenantId()).map(Tenant::active).orElse(false)
                && (user.admin() || inAGroup(user.id()));
    }

    private static String hash(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ServiceException.badRequest("user.passwordTooShort", "The password must have at least "
                    + MIN_PASSWORD_LENGTH + " characters",
                    Map.of("min", String.valueOf(MIN_PASSWORD_LENGTH)));
        }
        return PasswordHasher.hash(password);
    }

    private void refusePasswordWithWam(@Nullable String password) {
        if (wam.enabled() && password != null) {
            throw ServiceException.badRequest("user.wamNoPassword",
                    "Users sign in through the WAM proxy - they have no password");
        }
    }

    private static String requireUsername(TenantUserRequest request) {
        return requireUsername(request == null ? null : request.username());
    }

    /** 400 unless 3 to 100 letters, digits or . _ - @ - also for the user name a WAM proxy sends. */
    public static String requireUsername(@Nullable String username) {
        String trimmed = username == null ? "" : username.trim();
        if (!USERNAME.matcher(trimmed).matches()) {
            throw ServiceException.badRequest("user.usernameInvalid",
                    "Username must have 3 to 100 characters: letters, digits, . _ - @");
        }
        return trimmed;
    }

    private static String requireLanguage(@Nullable String language) {
        String trimmed = language == null ? "" : language.trim();
        if (trimmed.length() > MAX_LANGUAGE_LENGTH || !LANGUAGE.matcher(trimmed).matches()) {
            throw ServiceException.badRequest("user.languageInvalid",
                    "Language must be a language tag such as \"en\" or \"de\"");
        }
        return trimmed;
    }

    private static String requireDisplayName(TenantUserRequest request) {
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw ServiceException.badRequest("user.nameRequired", "Name is required");
        }
        return request.displayName().trim();
    }

    /**
     * Unique per tenant - the same person may have a user in several. The admin's username is taken in every tenant:
     * the login checks it first, so such a user could never log in.
     */
    private void assertUsernameAvailable(Long tenantId, String username, Long excludeId) {
        Optional<TenantUser> conflict = repository.findByTenantIdAndUsernameIgnoreCase(tenantId, username);
        if (conflict.isPresent() && !conflict.get().id().equals(excludeId) || username.equalsIgnoreCase(adminUsername)) {
            throw ServiceException.conflict("user.usernameTaken", "The username \"" + username + "\" is already taken",
                    Map.of("username", username));
        }
    }
}
