package de.alpdesk.services.tenant;

import de.alpdesk.dto.tenant.ApiKeyCreatedResponse;
import de.alpdesk.dto.tenant.ApiKeyResponse;
import de.alpdesk.entities.chat.ConversationOwner;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.entities.tenant.TenantApiKey;
import de.alpdesk.repositories.tenant.TenantApiKeyRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.annotation.Transactional;
import io.micronaut.core.annotation.Nullable;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.document.KnowledgeAreaService;
import de.alpdesk.services.mcp.McpConnectionService;
import de.alpdesk.services.task.TaskService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates, lists, revokes and verifies tenant API keys.
 * <p>
 * A key is {@code ai_} followed by 32 random bytes (base64url, 43 chars). Only its SHA-256 hash is
 * stored - a plain, unsalted hash is sufficient here because the key itself has 256 bits of
 * entropy, so there is nothing to brute-force or look up in a rainbow table, and it keeps the
 * lookup a single indexed query.
 */
@Singleton
public class ApiKeyService {

    public static final String KEY_PREFIX = "ai_";
    private static final int PREFIX_DISPLAY_LENGTH = KEY_PREFIX.length() + 8;
    /** lastUsedAt is informational - refreshing it at most this often keeps every call from writing. */
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(5);

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyService.class);

    private final SecureRandom random = new SecureRandom();
    private final TenantApiKeyRepository keyRepository;
    private final TenantRepository tenantRepository;
    private final KnowledgeAreaService areas;
    private final TaskService tasks;
    private final McpConnectionService software;
    private final ChatService chats;
    private final TransactionOperations<Connection> transactions;

    public ApiKeyService(TenantApiKeyRepository keyRepository, TenantRepository tenantRepository,
                         KnowledgeAreaService areas, TaskService tasks, McpConnectionService software,
                         ChatService chats, TransactionOperations<Connection> transactions) {
        this.keyRepository = keyRepository;
        this.tenantRepository = tenantRepository;
        this.areas = areas;
        this.tasks = tasks;
        this.software = software;
        this.chats = chats;
        this.transactions = transactions;
    }

    public List<TenantApiKey> findByTenant(Long tenantId) {
        return keyRepository.findByTenantIdOrderByCreatedAt(tenantId);
    }

    /** The tenant's keys as shown, with their areas, tasks and software. */
    public List<ApiKeyResponse> list(Long tenantId) {
        Map<Long, List<Long>> areasOfKeys = areas.areasOfApiKeys(tenantId);
        Map<Long, List<Long>> softwareOfKeys = software.connectionsOfApiKeys(tenantId);
        List<ApiKeyResponse> list = new ArrayList<>();
        for (TenantApiKey key : findByTenant(tenantId)) {
            list.add(ApiKeyResponse.from(key, areasOfKeys.getOrDefault(key.id(), List.of()),
                    tasks.taskIdsOfApiKey(key.id()), softwareOfKeys.getOrDefault(key.id(), List.of())));
        }
        return list;
    }

    /** The key as shown, with its areas, tasks and software. */
    public ApiKeyResponse response(TenantApiKey key) {
        return ApiKeyResponse.from(key, areas.areasOfApiKey(key.id()), tasks.taskIdsOfApiKey(key.id()),
                software.connectionsOfApiKey(key.id()));
    }

    /** A key's caller: its tenant, the key, and whether the key keeps its chat questions. */
    public record Caller(Long tenantId, Long apiKeyId, boolean keepsHistory) {
    }

    /**
     * keepsHistory null: true - its questions are kept as conversations; webSearch null: off; areaIds, taskIds and
     * connectionIds null: none. 400 for an unknown area, task or connection.
     */
    @Transactional
    public ApiKeyCreatedResponse create(Long tenantId, String name, @Nullable Boolean keepsHistory,
                                        @Nullable Boolean webSearch, @Nullable List<Long> areaIds,
                                        @Nullable List<Long> taskIds, @Nullable List<Long> connectionIds) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String plainKey = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        TenantApiKey saved = keyRepository.save(new TenantApiKey(null, tenantId, name.trim(),
                plainKey.substring(0, PREFIX_DISPLAY_LENGTH), hash(plainKey), !Boolean.FALSE.equals(keepsHistory),
                Boolean.TRUE.equals(webSearch), Instant.now(), null));
        areas.assignApiKey(tenantId, saved.id(), areaIds);
        tasks.assignApiKey(tenantId, saved.id(), taskIds);
        software.assignApiKey(tenantId, saved.id(), connectionIds);
        return new ApiKeyCreatedResponse(response(saved), plainKey);
    }

    /**
     * Renames the key, switches its history or web search or changes its areas, tasks or software - left out:
     * unchanged; empty for another tenant's key, 400 for an unknown area, task or connection.
     */
    @Transactional
    public Optional<TenantApiKey> update(Long tenantId, Long keyId, @Nullable String name, @Nullable Boolean keepsHistory,
                                         @Nullable Boolean webSearch, @Nullable List<Long> areaIds,
                                         @Nullable List<Long> taskIds, @Nullable List<Long> connectionIds) {
        if (name != null && name.isBlank()) {
            throw ServiceException.badRequest("apiKey.nameRequired", "Name is required");
        }
        // Locked: two saves of the key's areas at the same time would otherwise merge them (KnowledgeAreaService).
        Optional<TenantApiKey> existing = keyRepository.findByIdAndTenantIdForNoKeyUpdate(keyId, tenantId);
        existing.ifPresent(key -> areas.assignApiKey(tenantId, key.id(), areaIds));
        existing.ifPresent(key -> tasks.assignApiKey(tenantId, key.id(), taskIds));
        existing.ifPresent(key -> software.assignApiKey(tenantId, key.id(), connectionIds));
        return existing.map(key -> keyRepository.update(new TenantApiKey(
                key.id(), key.tenantId(), name == null ? key.name() : name.trim(), key.keyPrefix(), key.keyHash(),
                keepsHistory == null ? key.keepsHistory() : keepsHistory,
                webSearch == null ? key.webSearch() : webSearch, key.createdAt(), key.lastUsedAt())));
    }

    /**
     * Deletes the key - it stops working immediately - and, after the commit, its conversations with their files: no
     * other key may read them (they may quote knowledge areas only this key found). What cannot be removed stays behind
     * unreachable (key ids are never given out again); the revocation itself stands.
     */
    public boolean revoke(Long tenantId, Long keyId) {
        if (!transactions.executeWrite(_ -> keyRepository.deleteByIdAndTenantId(keyId, tenantId) > 0)) {
            return false;
        }
        try {
            chats.deleteAllOf(tenantId, ConversationOwner.apiKey(keyId));
        } catch (RuntimeException e) {
            LOG.warn("Conversations of revoked API key {} of tenant {} could not be removed: {}", keyId, tenantId,
                    e.toString());
        }
        return true;
    }

    /**
     * The caller of a presented key - its active tenant and whether it keeps its questions - or empty for an unknown
     * key or an inactive tenant. Blocking (JDBC) - callers on the event loop must shift it to another thread.
     */
    public Optional<Caller> authenticate(String plainKey) {
        if (plainKey == null || !plainKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        Optional<TenantApiKey> key = keyRepository.findByKeyHash(hash(plainKey));
        if (key.isEmpty()) {
            return Optional.empty();
        }
        Optional<Tenant> tenant = tenantRepository.findById(key.get().tenantId()).filter(Tenant::active);
        if (tenant.isPresent()) {
            touch(key.get());
        }
        return tenant.map(active -> new Caller(active.id(), key.get().id(), key.get().keepsHistory()));
    }

    private void touch(TenantApiKey key) {
        Instant now = Instant.now();
        if (key.lastUsedAt() == null || key.lastUsedAt().plus(LAST_USED_RESOLUTION).isBefore(now)) {
            keyRepository.updateLastUsedAt(key.id(), now);
        }
    }

    static String hash(String plainKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plainKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
