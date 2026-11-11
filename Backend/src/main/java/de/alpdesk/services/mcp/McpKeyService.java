package de.alpdesk.services.mcp;

import de.alpdesk.dto.mcp.McpKeyCreatedResponse;
import de.alpdesk.dto.mcp.McpKeyResponse;
import de.alpdesk.entities.mcp.TenantMcpKey;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.entities.mcp.McpAccessLevel;
import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.entities.mcp.McpConnectionProtocol;
import de.alpdesk.repositories.mcp.McpConnectionRepository;
import de.alpdesk.repositories.mcp.TenantMcpKeyRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.KnowledgeAreaService;
import de.alpdesk.services.task.TaskService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tenant's keys for its MCP server (docs/architecture/mcp.md "MCP keys", roadmap points 39 and 41), kept by its
 * tenant admins and the admin: several per tenant, each with a name, its knowledge areas, the connected software (each
 * read, or read and changed - roadmap point 68) and the scheduled tasks it may use and the web search - like the client software's API keys. Only the hash is stored and the plain key is shown once; renewing a key gives it a new secret and
 * keeps its settings.
 */
@Singleton
public class McpKeyService {

    public static final String KEY_PREFIX = "mcp_";
    private static final int PREFIX_DISPLAY_LENGTH = KEY_PREFIX.length() + 8;
    /** A description is sent with every list of tools - kept short (decided 5 November 2026). */
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    /** lastUsedAt is informational - refreshing it at most this often keeps every call from writing. */
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(5);

    private final SecureRandom random = new SecureRandom();
    private final TenantMcpKeyRepository keys;
    private final TenantRepository tenants;
    private final KnowledgeAreaService areas;
    private final McpConnectionRepository connections;
    private final TaskService tasks;
    private final TenantModelService models;
    private final McpConnectionService connectionService;
    private final McpTools mcpTools;

    public McpKeyService(TenantMcpKeyRepository keys, TenantRepository tenants, KnowledgeAreaService areas,
                         McpConnectionRepository connections, TaskService tasks, TenantModelService models,
                         McpConnectionService connectionService, McpTools mcpTools) {
        this.keys = keys;
        this.tenants = tenants;
        this.areas = areas;
        this.connections = connections;
        this.tasks = tasks;
        this.models = models;
        this.connectionService = connectionService;
        this.mcpTools = mcpTools;
    }

    /** A presented key's caller: its active tenant and the key. */
    public record Caller(Long tenantId, Long mcpKeyId, boolean mcp, boolean a2a) {
    }

    /** The tenant's keys, oldest first; 404 for an unknown tenant. */
    public List<McpKeyResponse> list(Long tenantId) {
        requireTenant(tenantId);
        Map<Long, List<Long>> areasOfKeys = areas.areasOfMcpKeys(tenantId);
        Map<Long, List<Long>> connectionsOfKeys = new HashMap<>();
        Map<Long, List<Long>> changingOfKeys = new HashMap<>();
        for (TenantMcpKeyRepository.ConnectionRow row : keys.findConnectionsOfTenant(tenantId)) {
            connectionsOfKeys.computeIfAbsent(row.mcpKeyId(), _ -> new ArrayList<>()).add(row.connectionId());
            if (McpAccessLevel.valueOf(row.level()) == McpAccessLevel.WRITE) {
                changingOfKeys.computeIfAbsent(row.mcpKeyId(), _ -> new ArrayList<>()).add(row.connectionId());
            }
        }
        Map<Long, List<Long>> tasksOfKeys = tasks.tasksOfMcpKeys(tenantId);
        List<McpKeyResponse> list = new ArrayList<>();
        for (TenantMcpKey key : keys.findByTenantIdOrderByCreatedAt(tenantId)) {
            list.add(McpKeyResponse.from(key, areasOfKeys.getOrDefault(key.id(), List.of()),
                    connectionsOfKeys.getOrDefault(key.id(), List.of()), changingOfKeys.getOrDefault(key.id(), List.of()),
                    tasksOfKeys.getOrDefault(key.id(), List.of())));
        }
        return list;
    }

    /** Whether the tenant has a key at all - its people's account page then shows how to connect. */
    public boolean hasKey(Long tenantId) {
        return keys.existsByTenantId(tenantId);
    }

    /**
     * A new key: name required (400), web search off, no description, area, software or task unless given, and no
     * software it may change in; 400 for an unknown area, connection or task, a description over
     * {@value #MAX_DESCRIPTION_LENGTH} characters or a software to change in it may not use or that does not pass
     * through, 404 for an unknown tenant.
     */
    @Transactional
    public McpKeyCreatedResponse create(Long tenantId, @Nullable String name, @Nullable String description,
                                        @Nullable Boolean webSearch,
                                        @Nullable List<Long> areaIds, @Nullable List<Long> connectionIds,
                                        @Nullable List<Long> changingConnectionIds,
                                        @Nullable List<Long> taskIds, @Nullable Boolean mcp, @Nullable Boolean a2a) {
        requireTenant(tenantId);
        boolean opensMcp = mcp == null || mcp;
        boolean opensA2a = Boolean.TRUE.equals(a2a);
        requireDoor(opensMcp, opensA2a);
        String plainKey = newPlainKey();
        TenantMcpKey saved = keys.save(new TenantMcpKey(null, tenantId, requireName(name),
                checkedDescription(description), displayPrefix(plainKey),
                hash(plainKey), Boolean.TRUE.equals(webSearch), opensMcp, opensA2a, Instant.now(), null));
        areas.assignMcpKey(tenantId, saved.id(), areaIds);
        assignConnections(tenantId, saved.id(), connectionIds, changingConnectionIds);
        tasks.assignMcpKey(tenantId, saved.id(), taskIds);
        return new McpKeyCreatedResponse(response(saved), plainKey);
    }

    /**
     * Renames the key, changes its description (empty: removed), switches its web search or changes its areas, its
     * software, the software it may change in or its tasks - left out: unchanged (the software it may change in: the
     * ones it had that it still may use); empty for another tenant's key, 400 as on create.
     */
    @Transactional
    public Optional<McpKeyResponse> update(Long tenantId, Long keyId, @Nullable String name,
                                           @Nullable String description, @Nullable Boolean webSearch, @Nullable List<Long> areaIds,
                                           @Nullable List<Long> connectionIds, @Nullable List<Long> changingConnectionIds,
                                           @Nullable List<Long> taskIds,
                                           @Nullable Boolean mcp, @Nullable Boolean a2a) {
        String newName = name == null ? null : requireName(name);
        String newDescription = description == null ? null : checkedDescription(description);
        // Locked: two saves of the key's areas at the same time would otherwise merge them.
        Optional<TenantMcpKey> existing = keys.findByIdAndTenantIdForNoKeyUpdate(keyId, tenantId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        TenantMcpKey key = existing.get();
        boolean opensMcp = mcp == null ? key.mcp() : mcp;
        boolean opensA2a = a2a == null ? key.a2a() : a2a;
        requireDoor(opensMcp, opensA2a);
        areas.assignMcpKey(tenantId, key.id(), areaIds);
        assignConnections(tenantId, key.id(), connectionIds, changingConnectionIds);
        tasks.assignMcpKey(tenantId, key.id(), taskIds);
        TenantMcpKey updated = keys.update(new TenantMcpKey(key.id(), key.tenantId(), newName == null ? key.name() : newName,
                description == null ? key.description() : newDescription, key.keyPrefix(), key.keyHash(), webSearch == null ? key.webSearch() : webSearch, opensMcp, opensA2a,
                key.createdAt(), key.lastUsedAt()));
        return Optional.of(response(updated));
    }

    /**
     * A new secret for the key - the previous one stops working at once. Name, areas, software, tasks, web search and its
     * creation stay (its place in the list); its last use starts anew with the new secret.
     */
    @Transactional
    public Optional<McpKeyCreatedResponse> renew(Long tenantId, Long keyId) {
        Optional<TenantMcpKey> existing = keys.findByIdAndTenantIdForNoKeyUpdate(keyId, tenantId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        TenantMcpKey key = existing.get();
        String plainKey = newPlainKey();
        TenantMcpKey renewed = keys.update(new TenantMcpKey(key.id(), key.tenantId(), key.name(), key.description(),
                displayPrefix(plainKey),
                hash(plainKey), key.webSearch(), key.mcp(), key.a2a(), key.createdAt(), null));
        return Optional.of(new McpKeyCreatedResponse(response(renewed), plainKey));
    }

    /** What the key releases, in the tenant admin's words; empty: none (or an unknown key). */
    public Optional<String> descriptionOf(Long mcpKeyId) {
        return keys.findById(mcpKeyId).map(TenantMcpKey::description);
    }

    /**
     * What the tenant's assistant does for an asker of this key beyond the documents: the web search the key allows
     * (with the tenant's catalog entry), code in the tenant's sandbox, the key's connected software. A general chat
     * reads it in the topic's description - without it, it kept those parts of a question and said it had no such tool
     * (the general chat's test, 8 November 2026). Empty: none of them - and for a tenant answering through its agent,
     * whose tools are the agent's matter. The software is what ask_assistant is offered, from the same list.
     */
    public Optional<String> offerOf(Long mcpKeyId) {
        Optional<TenantMcpKey> key = keys.findById(mcpKeyId);
        if (key.isEmpty() || connectionService.answeringAgent(key.get().tenantId()).isPresent()) {
            return Optional.empty();
        }
        List<String> offers = new ArrayList<>();
        if (key.get().webSearch() && hasWebSearch(key.get().tenantId())) {
            offers.add("search the web");
        }
        if (tenants.findById(key.get().tenantId()).map(McpKeyService::hasSandbox).orElse(false)) {
            offers.add("run Python and shell code");
        }
        List<String> software = mcpTools.forMcpKey(key.get().tenantId(), mcpKeyId).stream()
                .map(offered -> offered.connection().name()).distinct().toList();
        if (!software.isEmpty()) {
            offers.add("read the connected software " + String.join(", ", software));
        }
        if (offers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("It can also " + joined(offers) + " - hand it those parts of a question that concern the "
                + "company, with their full context.");
    }

    private boolean hasWebSearch(Long tenantId) {
        return models.findUsable(tenantId).stream().anyMatch(model -> model.kind() == ModelKind.WEB_SEARCH);
    }

    /** Configured is enough - checking the instances on every list of tools would wait on them; a call says if down. */
    private static boolean hasSandbox(Tenant tenant) {
        return tenant.sandboxToken() != null && tenant.sandboxUrls() != null && !tenant.sandboxUrls().isBlank();
    }

    /** "a", "a and b", "a, b and c". */
    private static String joined(List<String> parts) {
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    /** The ids of the connected software the key may use. */
    public List<Long> connectionsOf(Long mcpKeyId) {
        return keys.findConnectionIds(mcpKeyId);
    }

    /** Deletes the key with its areas, software and tasks - it stops working at once; false for another tenant's or an unknown key. */
    @Transactional
    public boolean delete(Long tenantId, Long keyId) {
        return keys.deleteByIdAndTenantId(keyId, tenantId) > 0;
    }

    /**
     * The caller of a presented key, or empty for an unknown key or an inactive tenant. Blocking (JDBC) - callers on
     * the event loop must shift it to another thread.
     */
    public Optional<Caller> authenticate(String plainKey) {
        if (plainKey == null || !plainKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        Optional<TenantMcpKey> key = keys.findByKeyHash(hash(plainKey));
        if (key.isEmpty() || tenants.findById(key.get().tenantId()).filter(Tenant::active).isEmpty()) {
            return Optional.empty();
        }
        touch(key.get());
        return Optional.of(new Caller(key.get().tenantId(), key.get().id(), key.get().mcp(), key.get().a2a()));
    }

    private McpKeyResponse response(TenantMcpKey key) {
        List<Long> connectionIds = new ArrayList<>();
        List<Long> changingIds = new ArrayList<>();
        for (McpConnectionRepository.AccessRow row : keys.findConnectionLevels(key.id())) {
            connectionIds.add(row.connectionId());
            if (row.accessLevel() == McpAccessLevel.WRITE) {
                changingIds.add(row.connectionId());
            }
        }
        return McpKeyResponse.from(key, areas.areasOfMcpKey(key.id()), connectionIds, changingIds,
                tasks.taskIdsOfMcpKey(key.id()));
    }

    /**
     * Replaces the key's software and the ones it may change in - in the caller's transaction. Both null: unchanged.
     * The software left out: the key's as it is; the ones to change in left out: those it had that it still may use. 400
     * for a connection that is not the tenant's, and for one to change in that the key may not use or that does not
     * pass through to MCP clients (an answer-only software's or an A2A agent's changing tools reach nobody here).
     */
    private void assignConnections(Long tenantId, Long mcpKeyId, @Nullable List<Long> connectionIds,
                                   @Nullable List<Long> changingConnectionIds) {
        if (connectionIds == null && changingConnectionIds == null) {
            return;
        }
        Map<Long, McpConnection> known = new HashMap<>();
        for (McpConnection connection : connections.findByTenantIdOrderByName(tenantId)) {
            known.put(connection.id(), connection);
        }
        Map<Long, McpAccessLevel> current = new LinkedHashMap<>();
        for (McpConnectionRepository.AccessRow row : keys.findConnectionLevels(mcpKeyId)) {
            current.put(row.connectionId(), row.accessLevel());
        }
        Set<Long> used = connectionIds == null ? current.keySet() : checkedConnections(connectionIds, known);
        Set<Long> changing = changingConnectionIds == null ? stillChanging(current, used)
                : checkedChanging(changingConnectionIds, used, known);
        keys.unassignConnections(mcpKeyId);
        for (Long connectionId : used) {
            McpAccessLevel level = changing.contains(connectionId) ? McpAccessLevel.WRITE : McpAccessLevel.READ;
            keys.assignConnection(mcpKeyId, connectionId, level.name());
        }
    }

    private static Set<Long> checkedConnections(List<Long> connectionIds, Map<Long, McpConnection> known) {
        Set<Long> checked = new LinkedHashSet<>();
        for (Long connectionId : connectionIds) {
            if (connectionId == null || !known.containsKey(connectionId)) {
                throw ServiceException.badRequest("mcpKey.unknownConnection", "Unknown connected software");
            }
            checked.add(connectionId);
        }
        return checked;
    }

    /** The software the key could change in before and still may use - a software taken away loses it. */
    private static Set<Long> stillChanging(Map<Long, McpAccessLevel> current, Set<Long> used) {
        Set<Long> changing = new HashSet<>();
        current.forEach((connectionId, level) -> {
            if (level == McpAccessLevel.WRITE && used.contains(connectionId)) {
                changing.add(connectionId);
            }
        });
        return changing;
    }

    private static Set<Long> checkedChanging(List<Long> changingConnectionIds, Set<Long> used,
                                             Map<Long, McpConnection> known) {
        Set<Long> checked = new HashSet<>();
        for (Long connectionId : changingConnectionIds) {
            if (connectionId == null || !known.containsKey(connectionId)) {
                throw ServiceException.badRequest("mcpKey.unknownConnection", "Unknown connected software");
            }
            if (!used.contains(connectionId)) {
                throw ServiceException.badRequest("mcpKey.changingNotUsed",
                        "The key may change only in software it may use");
            }
            McpConnection connection = known.get(connectionId);
            if (!connection.passesThrough() || connection.protocol() != McpConnectionProtocol.MCP) {
                throw ServiceException.badRequest("mcpKey.changingNotPassedThrough",
                        "The key may change only in software that goes directly to MCP clients",
                        Map.of("name", connection.name()));
            }
            checked.add(connectionId);
        }
        return checked;
    }

    private void touch(TenantMcpKey key) {
        Instant now = Instant.now();
        if (key.lastUsedAt() == null || key.lastUsedAt().plus(LAST_USED_RESOLUTION).isBefore(now)) {
            keys.updateLastUsedAt(key.id(), now);
        }
    }

    private String newPlainKey() {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static String displayPrefix(String plainKey) {
        return plainKey.substring(0, PREFIX_DISPLAY_LENGTH);
    }

    /** A key opens /mcp, /a2a or both - 400 for none. */
    private static void requireDoor(boolean mcp, boolean a2a) {
        if (!mcp && !a2a) {
            throw ServiceException.badRequest("mcpKey.noDoor", "The key must open /mcp, /a2a or both");
        }
    }

    private static String requireName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            throw ServiceException.badRequest("mcpKey.nameRequired", "The key's name is required");
        }
        return name.strip();
    }

    /** Stripped; null for none or an empty one (removes it); 400 over {@value #MAX_DESCRIPTION_LENGTH} characters. */
    private static @Nullable String checkedDescription(@Nullable String description) {
        String stripped = description == null ? "" : description.strip();
        if (stripped.length() > MAX_DESCRIPTION_LENGTH) {
            throw ServiceException.badRequest("mcpKey.descriptionTooLong", "The key's description may have at most "
                    + MAX_DESCRIPTION_LENGTH + " characters", Map.of("max", String.valueOf(MAX_DESCRIPTION_LENGTH)));
        }
        return stripped.isEmpty() ? null : stripped;
    }

    private void requireTenant(Long tenantId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw ServiceException.notFound("tenant.notFound", "Tenant not found");
        }
    }

    private static String hash(String plainKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plainKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
