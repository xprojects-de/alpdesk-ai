package de.alpdesk.services.tenant;

import de.alpdesk.dto.tenant.TenantRequest;
import de.alpdesk.entities.tenant.SearchLanguage;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.entities.tenant.UserGroup;
import de.alpdesk.repositories.tenant.UserGroupRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.security.SecretCipher;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.sandbox.Sandboxes;
import io.micronaut.core.annotation.Nullable;
import de.alpdesk.storage.FileStorage;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Tenant administration (admin only), including the tenant's own database. */
@Singleton
public class TenantService {

    private static final Logger LOG = LoggerFactory.getLogger(TenantService.class);
    /** The sandbox's own rule (app.py): a shorter token could be guessed. */
    private static final int MIN_SANDBOX_TOKEN = 32;
    private static final int MAX_SANDBOX_TOKEN = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository repository;
    private final TenantDatabases databases;
    private final TransactionOperations<Connection> transactions;
    private final FileStorage storage;
    private final SecretCipher cipher;
    private final Sandboxes sandboxes;
    private final UserGroupRepository groups;

    public TenantService(TenantRepository repository, TenantDatabases databases,
                         TransactionOperations<Connection> transactions, FileStorage storage, SecretCipher cipher,
                         Sandboxes sandboxes, UserGroupRepository groups) {
        this.groups = groups;
        this.repository = repository;
        this.databases = databases;
        this.transactions = transactions;
        this.storage = storage;
        this.cipher = cipher;
        this.sandboxes = sandboxes;
    }

    /** The key of the tenant's own login page: 128 random bits, too many to find one by trying. */
    public static String newLoginKey() {
        byte[] key = new byte[16];
        RANDOM.nextBytes(key);
        return HexFormat.of().formatHex(key);
    }

    /** The tenant's own sandbox as stored: its instances, the encrypted token and its hint - all null for none. */
    private record SandboxSettings(@Nullable String urls, @Nullable String token, @Nullable String tokenHint) {
        static final SandboxSettings NONE = new SandboxSettings(null, null, null);
    }

    public List<Tenant> findAll() {
        return repository.findAllOrderByName();
    }

    public Optional<Tenant> findById(Long id) {
        return repository.findById(id);
    }

    /**
     * Creates the tenant with its group "Standard" (no rights - a user gets in only through a group, its tenant admins
     * put people into this one), then its database - after the commit, since CREATE DATABASE cannot run in a transaction. If
     * the database cannot be created, the tenant is removed again. 409 if another tenant already has this name
     * (case-insensitive) or one of the sandbox addresses, 400 for a sandbox that is not valid.
     */
    public Tenant create(TenantRequest request) {
        String name = requireName(request);
        SandboxSettings sandbox = sandboxOf(request, SandboxSettings.NONE);
        Tenant tenant = transactions.executeWrite(_ -> {
            assertNameAvailable(name, null);
            assertSandboxAvailable(sandbox.urls(), null);
            Instant now = Instant.now();
            Tenant saved = repository.save(new Tenant(null, name, request.active() == null || request.active(),
                    blankToNull(request.contactEmail()), blankToNull(request.notes()), databases.newDatabaseName(),
                    Boolean.TRUE.equals(request.globalKeysAllowed()), Boolean.TRUE.equals(request.tasksAllowed()),
                    Boolean.TRUE.equals(request.a2aAllowed()),
                    sandbox.urls(), sandbox.token(),
                    sandbox.tokenHint(), null, null,
                    request.searchLanguage() != null ? request.searchLanguage() : SearchLanguage.SIMPLE,
                    newLoginKey(), now, now));
            groups.save(new UserGroup(null, saved.id(), UserGroupService.STANDARD_GROUP, null, false, now));
            return saved;
        });
        try {
            databases.ensure(tenant.databaseName());
        } catch (RuntimeException e) {
            LOG.error("Database {} of the new tenant {} could not be created", tenant.databaseName(), tenant.id(), e);
            transactions.executeWrite(_ -> repository.deleteCountingRows(tenant.id()));
            dropQuietly(tenant);
            throw ServiceException.unavailable("tenant.databaseNotCreated",
                    "The tenant's database could not be created - please try again.");
        }
        return tenant;
    }

    /**
     * 409 if another tenant already has this name (case-insensitive) or one of the sandbox addresses, 400 for a sandbox
     * that is not valid.
     */
    @Transactional
    public Optional<Tenant> update(Long id, TenantRequest request) {
        // Locked: the switches left out are merged from this read, another save in between would be undone.
        Optional<Tenant> existing = repository.findByIdForNoKeyUpdate(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        String name = requireName(request);
        assertNameAvailable(name, id);
        Tenant tenant = existing.get();
        SandboxSettings sandbox = sandboxOf(request,
                new SandboxSettings(tenant.sandboxUrls(), tenant.sandboxToken(), tenant.sandboxTokenHint()));
        assertSandboxAvailable(sandbox.urls(), id);
        return Optional.of(repository.update(new Tenant(id, name,
                request.active() != null ? request.active() : tenant.active(),
                blankToNull(request.contactEmail()), blankToNull(request.notes()), tenant.databaseName(),
                request.globalKeysAllowed() != null ? request.globalKeysAllowed() : tenant.globalKeysAllowed(),
                request.tasksAllowed() != null ? request.tasksAllowed() : tenant.tasksAllowed(),
                request.a2aAllowed() != null ? request.a2aAllowed() : tenant.a2aAllowed(),
                sandbox.urls(), sandbox.token(), sandbox.tokenHint(),
                tenant.embeddingModelId(), tenant.rerankingModelId(), tenant.searchLanguage(), tenant.loginKey(), tenant.createdAt(),
                Instant.now())));
    }

    /**
     * The tenant's sandbox after this request (docs/architecture/code-execution.md "One sandbox per tenant"): addresses
     * left out keep the current ones, empty ones remove the sandbox with its token; a token left out or empty keeps the
     * current one. Addresses and token come together or not at all - an instance without its token refuses every call.
     */
    private SandboxSettings sandboxOf(TenantRequest request, SandboxSettings current) {
        String newToken = blankToNull(request.sandboxToken());
        if (request.sandboxUrls() != null && request.sandboxUrls().isBlank()) {
            if (newToken != null) {
                throw ServiceException.badRequest("sandbox.tokenNeedsAddresses", "Sandbox token needs addresses");
            }
            return SandboxSettings.NONE;
        }
        String urls = request.sandboxUrls() != null ? normalizeSandboxUrls(request.sandboxUrls()) : current.urls();
        if (newToken != null) {
            if (urls == null) {
                throw ServiceException.badRequest("sandbox.tokenNeedsAddresses", "Sandbox token needs addresses");
            }
            String token = checkedSandboxToken(newToken);
            return new SandboxSettings(urls, cipher.encrypt(token), hint(token));
        }
        if (urls != null && current.token() == null) {
            throw ServiceException.badRequest("sandbox.addressesNeedToken", "Sandbox addresses need a token");
        }
        return new SandboxSettings(urls, current.token(), current.tokenHint());
    }

    /**
     * Comma separated, http or https, a host, no credentials, query or fragment. Plain HTTP on purpose: the instances
     * live in the installation's internal network, and the admin entering them is the operator, who is trusted - unlike
     * a tenant admin's MCP addresses.
     */
    private static String normalizeSandboxUrls(String urls) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String url : Sandboxes.addresses(urls)) {
            normalized.add(checkedSandboxUrl(url));
        }
        if (normalized.isEmpty()) {
            throw ServiceException.badRequest("sandbox.addressesEmpty", "Sandbox addresses are empty");
        }
        return String.join(",", normalized);
    }

    /**
     * 409 if another tenant already has one of these addresses: the container is what keeps tenants apart (SECURITY.md),
     * so no instance may ever serve two of them - however the address is spelled.
     */
    private void assertSandboxAvailable(@Nullable String urls, @Nullable Long tenantId) {
        if (urls == null) {
            return;
        }
        List<String> addresses = Sandboxes.addresses(urls);
        for (Tenant other : repository.findBySandboxUrlsIsNotNull()) {
            if (other.id().equals(tenantId)) {
                continue;
            }
            for (String address : Sandboxes.addresses(other.sandboxUrls())) {
                if (addresses.stream().anyMatch(address::equalsIgnoreCase)) {
                    throw ServiceException.conflict("sandbox.addressTaken", "Sandbox address " + address
                            + " already belongs to tenant \"" + other.name()
                            + "\" - every tenant needs instances of its own",
                            Map.of("address", address, "tenant", other.name()));
                }
            }
        }
    }

    private static String checkedSandboxUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw ServiceException.badRequest("sandbox.addressInvalid", "Sandbox address not valid: " + url,
                    Map.of("address", url));
        }
        boolean web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        if (!web || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw ServiceException.badRequest("sandbox.addressInvalid", "Sandbox address not valid: " + url,
                    Map.of("address", url));
        }
        return url;
    }

    private static String checkedSandboxToken(String token) {
        if (token.length() < MIN_SANDBOX_TOKEN || token.length() > MAX_SANDBOX_TOKEN
                || token.chars().anyMatch(Character::isWhitespace)) {
            throw ServiceException.badRequest("sandbox.tokenInvalid", "Sandbox token must have " + MIN_SANDBOX_TOKEN
                    + " to " + MAX_SANDBOX_TOKEN + " characters and no spaces",
                    Map.of("min", String.valueOf(MIN_SANDBOX_TOKEN), "max", String.valueOf(MAX_SANDBOX_TOKEN)));
        }
        return token;
    }

    /** A token's last characters - enough to recognise it, too few to guess it from. */
    private static String hint(String token) {
        return "…" + token.substring(token.length() - 4);
    }

    /**
     * The admin's check of the tenant's sandbox: every instance with the tenant's token - an address or token typed
     * wrong would otherwise only show as missing tools in the chat. Empty for an unknown tenant, no entry for a tenant
     * without a sandbox. Holds no transaction: it waits for the instances.
     */
    public Optional<SandboxChecks> checkSandbox(Long id) {
        return repository.findById(id).map(tenant -> {
            Map<String, SandboxCheck> checks = new LinkedHashMap<>();
            if (tenant.sandboxToken() == null) {
                return new SandboxChecks(checks, true);
            }
            String token = cipher.decrypt(tenant.sandboxToken());
            List<String> urls = Sandboxes.addresses(tenant.sandboxUrls());
            for (String url : urls) {
                checks.put(url, new SandboxCheck(sandboxes.check(url, token), sandboxes.video(url), sandboxes.extras(url)));
            }
            return new SandboxChecks(checks, sandboxes.extrasAgree(urls));
        });
    }

    /**
     * The admin's check: every instance, and whether those answering carry the same README of their own programs - the
     * model gets it only then.
     */
    public record SandboxChecks(Map<String, SandboxCheck> instances, boolean extrasAgree) {
    }

    /**
     * One instance as the admin's check found it: whether it is the video image and whether it carries a README of its
     * own programs (both null when it did not answer).
     */
    public record SandboxCheck(Sandboxes.Status status, @Nullable Boolean video, @Nullable Boolean extras) {
    }

    /**
     * Deletes the tenant and, via ON DELETE CASCADE, everything of it in the management database, then - after the
     * commit - its own database and its files. A database or files that cannot be removed stay behind unreachable (the
     * database's name is never given out again, the files' prefix holds the tenant id, which is never reused); the
     * deletion itself stands.
     */
    public boolean delete(Long id) {
        Optional<Tenant> deleted = transactions.executeWrite(_ -> repository.findByIdForUpdate(id)
                .filter(tenant -> repository.deleteCountingRows(tenant.id()) > 0));
        if (deleted.isEmpty()) {
            return false;
        }
        // Before removing them: if the process stops now, the log tells what may be left behind.
        LOG.info("Tenant {} deleted - removing its database {} and its files {}", id, deleted.get().databaseName(),
                storagePrefix(id));
        dropQuietly(deleted.get());
        try {
            storage.deleteAll(storagePrefix(id));
        } catch (RuntimeException e) {
            LOG.warn("Files of deleted tenant {} could not be removed: {}", id, e.toString());
        }
        return true;
    }

    /**
     * Drops the database of a tenant that is gone - a database that cannot be dropped (the server briefly out of reach)
     * stays behind unused and is only logged: the tenant is removed either way, and the caller's answer must not turn
     * into a 500.
     */
    private void dropQuietly(Tenant tenant) {
        try {
            databases.drop(tenant.databaseName());
        } catch (RuntimeException e) {
            LOG.warn("Database {} of removed tenant {} could not be dropped: {}", tenant.databaseName(), tenant.id(),
                    e.toString());
        }
    }

    /** Where the tenant's files live in the {@link FileStorage} - every key of the tenant starts with it. */
    public static String storagePrefix(Long tenantId) {
        return "tenants/" + tenantId + "/";
    }

    private static String requireName(TenantRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ServiceException.badRequest("tenant.nameRequired", "Name is required");
        }
        return request.name().trim();
    }

    private void assertNameAvailable(String name, Long excludeId) {
        Optional<Tenant> conflict = repository.findByNameIgnoreCase(name);
        if (conflict.isPresent() && !conflict.get().id().equals(excludeId)) {
            throw ServiceException.conflict("tenant.nameTaken", "A tenant named \"" + name + "\" already exists",
                    Map.of("name", name));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
