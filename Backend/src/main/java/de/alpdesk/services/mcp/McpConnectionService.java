package de.alpdesk.services.mcp;

import de.alpdesk.dto.mcp.McpAccessResponse;
import de.alpdesk.dto.mcp.McpConnectionRequest;
import de.alpdesk.dto.mcp.McpConnectionCheckResponse;
import de.alpdesk.dto.mcp.McpConnectionResponse;
import de.alpdesk.dto.mcp.McpPromptArgument;
import de.alpdesk.dto.mcp.McpPromptResponse;
import de.alpdesk.dto.mcp.McpToolResponse;
import de.alpdesk.entities.mcp.McpAccessLevel;
import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.entities.mcp.McpConnectionProtocol;
import de.alpdesk.entities.mcp.McpPrompt;
import de.alpdesk.entities.mcp.McpTool;
import de.alpdesk.repositories.mcp.McpConnectionRepository;
import de.alpdesk.repositories.mcp.McpPromptRepository;
import de.alpdesk.repositories.mcp.McpToolRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.repositories.tenant.UserGroupRepository;
import de.alpdesk.security.SecretCipher;
import de.alpdesk.services.a2a.A2aClient;
import de.alpdesk.services.ai.ModelServers;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.oauth.AuthorizationServer;
import de.alpdesk.services.oauth.OAuthAddresses;
import de.alpdesk.services.oauth.OAuthClient;
import de.alpdesk.services.oauth.OAuthMetadata;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.json.JsonMapper;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tenant's connected software (docs/architecture/mcp.md "MCP clients"), kept by its tenant admins and the admin:
 * the connection (address, key), its tools and prompts - read from the server as soon as the connection is made, each
 * off until turned on - and each user group's level of access. The connection's key is stored encrypted and stays with its
 * host.
 */
@Singleton
public class McpConnectionService {

    public static final int MAX_NAME = 80;
    /**
     * The longest instructions of a server kept: the MCP server passes a key's connections' instructions on together
     * with its own, and Claude Code reads at most 4,096 characters of them (roadmap point 41, decided).
     */
    public static final int MAX_INSTRUCTIONS = 2_000;
    private static final int MAX_KEY_LENGTH = 4096;

    private final McpConnectionRepository connections;
    private final McpToolRepository tools;
    private final McpPromptRepository prompts;
    private final TenantRepository tenants;
    private final UserGroupRepository groups;
    private final McpAddresses addresses;
    private final McpClient client;
    private final A2aClient a2a;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final TransactionOperations<Connection> transactions;
    private final OAuthClient oauth;
    private final OAuthMetadata oauthMetadata;
    private final OAuthAddresses oauthAddresses;

    public McpConnectionService(McpConnectionRepository connections, McpToolRepository tools,
                                McpPromptRepository prompts, TenantRepository tenants, UserGroupRepository groups,
                                McpAddresses addresses, McpClient client, A2aClient a2a, SecretCipher cipher,
                                JsonMapper json, TransactionOperations<Connection> transactions, OAuthClient oauth,
                                OAuthMetadata oauthMetadata, OAuthAddresses oauthAddresses) {
        this.connections = connections;
        this.tools = tools;
        this.prompts = prompts;
        this.tenants = tenants;
        this.groups = groups;
        this.addresses = addresses;
        this.client = client;
        this.a2a = a2a;
        this.cipher = cipher;
        this.json = json;
        this.transactions = transactions;
        this.oauth = oauth;
        this.oauthMetadata = oauthMetadata;
        this.oauthAddresses = oauthAddresses;
    }

    /** The tenant's connections with their tools; 404 for an unknown tenant. */
    public List<McpConnectionResponse> list(Long tenantId) {
        requireTenant(tenantId);
        return connections.findByTenantIdOrderByName(tenantId).stream().map(connection -> response(connection, null)).toList();
    }

    /**
     * Connects the software and reads its tools at once - all off. A server that cannot be read keeps the connection;
     * the answer says why (toolsError), and "reload" tries again. 400 for invalid input or an address that is not
     * allowed, 409 for a name the tenant has - and for an A2A agent of a tenant the admin did not allow A2A.
     */
    public McpConnectionResponse create(Long tenantId, McpConnectionRequest request) {
        requireTenant(tenantId);
        String name = name(request);
        McpConnectionProtocol protocol = request.protocol() == null ? McpConnectionProtocol.MCP : request.protocol();
        if (protocol == McpConnectionProtocol.A2A) {
            requireA2aAllowed(tenantId);
        }
        String baseUrl = addresses.normalize(request.baseUrl());
        String key = blankToNull(request.apiKey());
        Instant now = Instant.now();
        boolean answersChat = answersChat(protocol, request.answersChat(), false);
        McpConnection saved = transactions.executeWrite(_ -> {
            if (answersChat) {
                requireNoOtherAnswering(tenantId, null);
            }
            return connections.save(new McpConnection(null, tenantId, name,
                baseUrl, key == null ? null : cipher.encrypt(checkedKey(key)), key == null ? null : hint(key), null, false,
                null, protocol, answersChat, now, now));
        });
        return load(saved);
    }

    /**
     * Changes name, address or key - another host than before drops the key unless a new one comes. Another address is
     * taken as another software: its tools are read anew, all off - the old tools' choices (on, changing or not) must
     * not pass to tools of the same name there. The server is asked after the commit, outside a transaction.
     */
    public Optional<McpConnectionResponse> update(Long tenantId, Long connectionId, McpConnectionRequest request) {
        Optional<Updated> updated = transactions.executeWrite(_ -> save(tenantId, connectionId, request));
        return updated.map(result -> result.newAddress() ? load(result.connection()) : response(result.connection(), null));
    }

    private record Updated(McpConnection connection, boolean newAddress) {
    }

    /**
     * Under the row's lock, as every write of a connection and its tools: a reload or a switch at the same moment
     * would otherwise be overwritten with what was read before it. The address is resolved before - it may take a
     * while.
     */
    private Optional<Updated> save(Long tenantId, Long connectionId, McpConnectionRequest request) {
        String name = name(request);
        String baseUrl = addresses.normalize(request.baseUrl());
        Optional<McpConnection> existing = connections.findByIdAndTenantIdForNoKeyUpdate(connectionId, tenantId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        McpConnection connection = existing.get();
        if (request.protocol() != null && request.protocol() != connection.protocol()) {
            throw ServiceException.badRequest("mcpConnection.protocolFixed",
                    "The protocol of a connection cannot change - make a new connection");
        }
        String key = blankToNull(request.apiKey());
        String storedKey;
        String keyHint;
        if (Boolean.TRUE.equals(request.removeApiKey())) {
            storedKey = null;
            keyHint = null;
        } else if (key != null) {
            storedKey = cipher.encrypt(checkedKey(key));
            keyHint = hint(key);
            // A key replaces a sign-in (docs/architecture/mcp.md "Signing in to the software's server").
            oauth.forget(connectionId);
        } else if (ModelServers.origin(baseUrl).equals(ModelServers.origin(connection.baseUrl()))) {
            storedKey = connection.apiKey();
            keyHint = connection.keyHint();
        } else {
            storedKey = null;
            keyHint = null;
        }
        boolean answersChat = answersChat(connection.protocol(), request.answersChat(), connection.answersChat());
        if (answersChat && !connection.answersChat()) {
            requireNoOtherAnswering(tenantId, connectionId);
        }
        boolean newAddress = !baseUrl.equals(connection.baseUrl());
        if (newAddress && connection.protocol() == McpConnectionProtocol.A2A) {
            requireA2aAllowed(tenantId);
        }
        if (newAddress) {
            tools.deleteByConnectionId(connectionId);
            prompts.deleteByConnectionId(connectionId);
            // The sign-in was for the old address (its resource) - the new one is found again.
            oauth.forget(connectionId);
        }
        String clientId = blankToNull(request.clientId());
        if (clientId != null && !newAddress && key == null) {
            oauth.enterClient(connectionId, name, clientId, blankToNull(request.clientSecret()));
        }
        McpConnection updated = connections.update(new McpConnection(connectionId, tenantId, name, baseUrl, storedKey,
                keyHint, newAddress ? null : connection.toolsLoadedAt(), connection.passesThrough(),
                newAddress ? null : connection.instructions(), connection.protocol(), answersChat, connection.createdAt(),
                Instant.now()));
        return Optional.of(new Updated(updated, newAddress));
    }

    @Transactional
    public boolean delete(Long tenantId, Long connectionId) {
        return connections.deleteByIdAndTenantId(connectionId, tenantId) > 0;
    }

    /**
     * Reads the server's tools again: new ones come in off, dropped ones are kept but no longer offered, and what the
     * admin chose (on/off, changing) stays - an A2A agent's card likewise, its skills replaced. The server is asked
     * outside a transaction - it can take seconds. 409 for an A2A agent of a tenant no longer allowed A2A.
     */
    public Optional<McpConnectionResponse> reload(Long tenantId, Long connectionId) {
        Optional<McpConnection> connection = connections.findByIdAndTenantId(connectionId, tenantId);
        if (connection.isPresent() && connection.get().protocol() == McpConnectionProtocol.A2A) {
            requireA2aAllowed(tenantId);
        }
        return connection.map(this::load);
    }

    /** Turns a tool on or off, or corrects whether it changes something; empty for another tenant's. */
    @Transactional
    public Optional<McpToolResponse> updateTool(Long tenantId, Long connectionId, Long toolId,
                                                @Nullable Boolean enabled, @Nullable Boolean changes) {
        if (connections.findByIdAndTenantIdForNoKeyUpdate(connectionId, tenantId).isEmpty()) {
            return Optional.empty();
        }
        return tools.findByIdAndConnectionId(toolId, connectionId).map(tool -> McpToolResponse.from(tools.update(
                new McpTool(tool.id(), tool.connectionId(), tool.name(), tool.description(), tool.inputSchema(),
                        changes != null ? changes : tool.changes(), tool.readOnlyHint(),
                        enabled != null ? enabled : tool.enabled(), tool.offered()))));
    }

    /** Turns a prompt on or off; empty for another tenant's. */
    @Transactional
    public Optional<McpPromptResponse> updatePrompt(Long tenantId, Long connectionId, Long promptId,
                                                    @Nullable Boolean enabled) {
        if (connections.findByIdAndTenantIdForNoKeyUpdate(connectionId, tenantId).isEmpty()) {
            return Optional.empty();
        }
        return prompts.findByIdAndConnectionId(promptId, connectionId).map(prompt -> McpPromptResponse.from(
                prompts.update(new McpPrompt(prompt.id(), prompt.connectionId(), prompt.name(), prompt.title(),
                        prompt.description(), prompt.arguments(), enabled != null ? enabled : prompt.enabled(),
                        prompt.offered())), json));
    }

    /** Every user group of the tenant with its level for the connection; empty for another tenant's connection. */
    public Optional<List<McpAccessResponse>> access(Long tenantId, Long connectionId) {
        if (connections.findByIdAndTenantId(connectionId, tenantId).isEmpty()) {
            return Optional.empty();
        }
        Map<Long, McpAccessLevel> levels = new HashMap<>();
        for (UserGroupRepository.GroupLevel row : groups.findAccessOfConnection(connectionId)) {
            levels.put(row.groupId(), McpAccessLevel.valueOf(row.level()));
        }
        return Optional.of(groups.findByTenantIdOrderByName(tenantId).stream()
                .map(group -> new McpAccessResponse(group.id(), group.name(), group.wamGroup(), levels.get(group.id())))
                .toList());
    }

    /**
     * Whether MCP keys with the connection get its reading tools, prompts and instructions directly ("Direkt an
     * MCP-Clients") - off, they use it through ask_assistant only. Which keys may use it at all is theirs to choose.
     * 400 without a value, empty for another tenant's connection.
     */
    @Transactional
    public Optional<McpConnectionResponse> setPassThrough(Long tenantId, Long connectionId,
                                                         @Nullable Boolean passesThrough) {
        if (passesThrough == null) {
            throw ServiceException.badRequest("mcpConnection.passThroughRequired",
                    "Whether the connection is passed on to MCP clients is required");
        }
        return connections.findByIdAndTenantIdForNoKeyUpdate(connectionId, tenantId).map(connection -> {
            if (connection.protocol() == McpConnectionProtocol.A2A) {
                throw ServiceException.conflict("mcpConnection.notForA2a",
                        "An A2A agent has no tools to pass on to MCP clients");
            }
            if (!passesThrough) {
                // What a key may change in goes only directly to its client - switched on again, nobody chose it.
                connections.mcpKeysReadOnly(connection.id());
            }
            return response(connections.update(new McpConnection(connection.id(), connection.tenantId(),
                    connection.name(), connection.baseUrl(), connection.apiKey(), connection.keyHint(),
                    connection.toolsLoadedAt(), passesThrough, connection.instructions(), connection.protocol(),
                    connection.answersChat(), connection.createdAt(), Instant.now())), null);
        });
    }

    /** The connected software the client software's API key may use through /api/v1/chat (roadmap point 42). */
    public List<Long> connectionsOfApiKey(Long apiKeyId) {
        return connections.findConnectionIdsOfApiKey(apiKeyId);
    }

    /** The connected software of every API key of the tenant, by key - for the list of keys. */
    public Map<Long, List<Long>> connectionsOfApiKeys(Long tenantId) {
        Map<Long, List<Long>> byKey = new HashMap<>();
        for (McpConnectionRepository.ApiKeyConnectionRow row : connections.findApiKeyConnectionsOfTenant(tenantId)) {
            byKey.computeIfAbsent(row.apiKeyId(), _ -> new ArrayList<>()).add(row.connectionId());
        }
        return byKey;
    }

    /**
     * Replaces the connected software the API key may use - in the caller's transaction, which holds the key locked;
     * null: unchanged. 400 for a connection that is not the tenant's.
     */
    public void assignApiKey(Long tenantId, Long apiKeyId, @Nullable List<Long> connectionIds) {
        if (connectionIds == null) {
            return;
        }
        Set<Long> known = new HashSet<>();
        for (McpConnection connection : connections.findByTenantIdOrderByName(tenantId)) {
            known.add(connection.id());
        }
        Set<Long> checked = new LinkedHashSet<>();
        for (Long connectionId : connectionIds) {
            if (connectionId == null || !known.contains(connectionId)) {
                throw ServiceException.badRequest("apiKey.unknownConnection", "Unknown connected software");
            }
            checked.add(connectionId);
        }
        connections.unassignApiKey(apiKeyId);
        checked.forEach(connectionId -> connections.assignApiKey(apiKeyId, connectionId));
    }

    /** A tool of a connection, as a scheduled task names them. */
    public record TaskTool(McpConnection connection, McpTool tool) {
    }

    /**
     * The tool of this name of the tenant's connection of this name (case does not matter for the connection) - for a
     * task's check. Only a tool turned on: a task never calls one the tenant admin did not allow.
     */
    public Optional<TaskTool> toolForTask(Long tenantId, String connectionName, String toolName) {
        for (McpConnection connection : connections.findByTenantIdOrderByName(tenantId)) {
            if (connection.protocol() == McpConnectionProtocol.MCP && connection.name().equalsIgnoreCase(connectionName.strip())) {
                return tools.findByConnectionIdOrderByName(connection.id()).stream()
                        .filter(tool -> tool.name().equals(toolName.strip()) && tool.enabled() && tool.offered())
                        .findFirst().map(tool -> new TaskTool(connection, tool));
            }
        }
        return Optional.empty();
    }

    /** The tool's input schema as last read - null when the connection or the tool is gone. */
    public @Nullable String toolSchema(Long tenantId, Long connectionId, String toolName) {
        if (connections.findByIdAndTenantId(connectionId, tenantId).isEmpty()) {
            return null;
        }
        return tools.findByConnectionIdOrderByName(connectionId).stream().filter(tool -> tool.name().equals(toolName))
                .findFirst().map(McpTool::inputSchema).orElse(null);
    }

    /**
     * A scheduled task's call (docs/architecture/tasks.md "call_tool"): the tool must still be there and turned on -
     * 409 task.toolUnavailable otherwise. The task's text named it, which is the approval of a changing one; no group's
     * level counts, there is no user. A source's call (reading) is refused with 409 task.toolChanges when the tool
     * became a changing one since the task was saved - a tenant admin's correction or a reload of the software's
     * tools: nobody approved it. Not in a transaction: the software may take seconds to answer.
     */
    public McpClient.CallResult callForTask(Long tenantId, Long connectionId, String toolName,
                                            Map<String, Object> arguments, boolean reading) {
        McpConnection connection = connections.findByIdAndTenantId(connectionId, tenantId).orElse(null);
        McpTool tool = connection == null || connection.protocol() != McpConnectionProtocol.MCP ? null : tools.findByConnectionIdOrderByName(connectionId).stream()
                .filter(candidate -> candidate.name().equals(toolName) && candidate.enabled() && candidate.offered())
                .findFirst().orElse(null);
        if (tool == null) {
            throw ServiceException.conflict("task.toolUnavailable", "The tool " + toolName + " is no longer offered "
                    + "or turned on - a tenant admin looks at the connected software", Map.of("tool", toolName));
        }
        if (reading && tool.changes()) {
            throw ServiceException.conflict("task.toolChanges", "The tool " + toolName + " changes something - a "
                    + "call_tool step without input calls only reading tools", Map.of("tool", toolName));
        }
        return client.callTool(tenantId, connection.name(), connection.baseUrl(), credential(connection), toolName,
                arguments);
    }

    /**
     * How a call to the connection signs in: the access token of its OAuth sign-in (renewed when needed - 502
     * oauth.signInNeeded when there is none), else its key, else nothing. Every caller - the chat, the proxy, the
     * prompts, the API keys' software, the tasks - goes through here.
     */
    public Credential credential(McpConnection connection) {
        if (oauth.find(connection.id()).isPresent()) {
            return signedIn(connection);
        }
        return Credential.key(key(connection));
    }

    private Credential signedIn(McpConnection connection) {
        long id = connection.id();
        String name = connection.name();
        // The token of this address only: a call still open to an address the connection has left gets none.
        String address = connection.baseUrl();
        return new Credential(() -> oauth.accessToken(id, name, address),
                failed -> oauth.refresh(id, name, address, failed),
                () -> oauth.signInLost(id, "the server refused the renewed token too - sign in again"));
    }

    /**
     * Starts the sign-in at the connection's server: the address of its sign-in page to send the browser to. 409
     * oauth.notAvailable when the server never asked for one; 400 for a page to return to that is not ours. Empty for
     * another tenant's connection. Not in a transaction: obtaining a client id may register at the server.
     */
    public Optional<URI> startSignIn(Long tenantId, Long connectionId, @Nullable String returnUrl, String origin,
                                     @Nullable Long userId) {
        Optional<McpConnection> connection = connections.findByIdAndTenantId(connectionId, tenantId);
        if (connection.isEmpty()) {
            return Optional.empty();
        }
        if (connection.get().protocol() == McpConnectionProtocol.A2A) {
            requireA2aAllowed(tenantId);
        }
        String back = oauthAddresses.returnUrl(returnUrl, origin);
        return Optional.of(oauth.startSignIn(connectionId, connection.get().name(), origin, back, userId));
    }

    /**
     * Completes the sign-in on the page the browser came back to, with the server's answer our callback passed on -
     * for the person who started it only (userId; null: the admin): for anyone else 409 oauth.stateUnknown, and the
     * sign-in stays pending for its owner. Signed in, the tools or the card are read with the new token. Empty for
     * another tenant's connection. Not in a transaction: the code is exchanged at the server.
     */
    public Optional<McpConnectionResponse> completeSignIn(Long tenantId, Long connectionId, @Nullable String callback,
                                                          String origin, @Nullable Long userId) {
        Optional<McpConnection> connection = connections.findByIdAndTenantId(connectionId, tenantId);
        if (connection.isEmpty()) {
            return Optional.empty();
        }
        if (connection.get().protocol() == McpConnectionProtocol.A2A) {
            requireA2aAllowed(tenantId);
        }
        OAuthClient.Completed completed = oauth.complete(callbackUri(callback, origin), tenantId, connectionId, userId);
        if (!completed.ok()) {
            throw signInNotCompleted(completed.errorCode(), connection.get().name());
        }
        return afterSignIn(tenantId, connectionId);
    }

    /** Nimbus parses an absolute address only - our callback's own, with the query the server sent. */
    private URI callbackUri(@Nullable String callback, String origin) {
        try {
            return URI.create(oauthAddresses.callback(origin) + "?" + (callback == null ? "" : callback));
        } catch (IllegalArgumentException e) {
            return URI.create(oauthAddresses.callback(origin));
        }
    }

    /** The completion refused: a sign-in that is not (or no longer) this person's 409, the server's refusal 502. */
    private static ServiceException signInNotCompleted(String code, String connectionName) {
        Map<String, String> params = Map.of("name", connectionName);
        return switch (code) {
            case "oauth.stateUnknown" -> ServiceException.conflict("oauth.stateUnknown",
                    connectionName + ": no sign-in of yours waits for this answer - start it again", params);
            case "oauth.stateExpired" -> ServiceException.conflict("oauth.stateExpired",
                    connectionName + ": the sign-in took too long - start it again", params);
            case "oauth.issuerMismatch" -> new ServiceException(HttpStatus.BAD_GATEWAY, "oauth.issuerMismatch",
                    connectionName + ": the answer of the sign-in came from another server", params);
            case "oauth.exchangeFailed" -> new ServiceException(HttpStatus.BAD_GATEWAY, "oauth.exchangeFailed",
                    connectionName + ": the sign-in server gave no token", params);
            default -> new ServiceException(HttpStatus.BAD_GATEWAY, "oauth.denied",
                    connectionName + ": the sign-in was refused at the server", params);
        };
    }

    /** Removes the sign-in; the tools stay as they are until a key or a new sign-in comes. Empty for another tenant's. */
    @Transactional
    public Optional<McpConnectionResponse> removeSignIn(Long tenantId, Long connectionId) {
        return connections.findByIdAndTenantIdForNoKeyUpdate(connectionId, tenantId).map(connection -> {
            oauth.forget(connectionId);
            return response(connection, null);
        });
    }

    /**
     * After the browser came back signed in: the key goes (the sign-in replaces it), the tools or the card are read
     * with the new token. A failure there lands in toolsError; the sign-in stays. The server is asked outside a
     * transaction.
     */
    public Optional<McpConnectionResponse> afterSignIn(Long tenantId, Long connectionId) {
        Optional<McpConnection> keyless = transactions.executeWrite(_ -> connections
                .findByIdAndTenantIdForNoKeyUpdate(connectionId, tenantId).map(connection -> connections.update(
                        new McpConnection(connection.id(), connection.tenantId(), connection.name(),
                                connection.baseUrl(), null, null, connection.toolsLoadedAt(), connection.passesThrough(),
                                connection.instructions(), connection.protocol(), connection.answersChat(),
                                connection.createdAt(), Instant.now()))));
        return keyless.map(this::load);
    }

    /**
     * The connection's key in plain text for a call - null when the server asks for none. 502 when it can no longer be
     * decrypted (APP_SECRET_KEY changed): a refusal the chat's model, an MCP client and the tenant admin read, who
     * enters the key again - not an unexpected error that ends a chat answer or a reload.
     */
    public String key(McpConnection connection) {
        if (connection.apiKey() == null) {
            return null;
        }
        try {
            return cipher.decrypt(connection.apiKey());
        } catch (IllegalStateException e) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "mcpServer.keyUnreadable", connection.name()
                    + ": our stored key can no longer be read - enter it again in the connection",
                    Map.of("name", connection.name()));
        }
    }

    /**
     * Reads the tools, the prompts and the server's instructions, in one session. Without its tools nothing is
     * changed; a server whose prompts cannot be read keeps its tools read and its prompts as they were, and says why
     * (promptsError). The server can take minutes: what it sent is stored on the connection as it is then - a key, an
     * address or an access changed meanwhile stays, and tools read from an address no longer the connection's are
     * dropped.
     */
    private McpConnectionResponse load(McpConnection connection) {
        if (connection.protocol() == McpConnectionProtocol.A2A) {
            return loadCard(connection);
        }
        Credential reading;
        try {
            reading = readCredential(connection);
        } catch (ServiceException e) {
            return response(connection, e.getMessage(), null);
        }
        McpClient.Offer offer;
        try {
            offer = client.read(connection.tenantId(), connection.name(), connection.baseUrl(), reading);
        } catch (McpClient.KeyRefused refused) {
            try {
                return signInFound(connection, refused.wwwAuthenticate()) ? response(connection, null)
                        : response(connection, refused.getMessage(), null);
            } catch (ServiceException e) {
                return response(connection, e.getMessage(), null);
            }
        } catch (ServiceException e) {
            return response(connection, e.getMessage(), null);
        }
        McpConnection loaded = transactions.executeWrite(_ -> {
            McpConnection current = connections.findByIdAndTenantIdForNoKeyUpdate(connection.id(),
                    connection.tenantId()).orElse(null);
            if (current == null || !current.baseUrl().equals(connection.baseUrl())) {
                return current;
            }
            if (!reading.signedIn()) {
                // Read without a sign-in: a grant nobody signed in to is no longer asked for - it would refuse every call.
                oauth.find(current.id()).filter(grant -> grant.accessToken() == null)
                        .ifPresent(_ -> oauth.forget(current.id()));
            }
            if (offer.prompts() != null) {
                syncPrompts(current, offer.prompts());
            }
            return sync(current, offer.tools(), offer.instructions());
        });
        return response(loaded != null ? loaded : connection, null, offer.promptsError());
    }

    /**
     * What reading the tools sends: the sign-in's token once there is one - before, the key or nothing, so a server
     * that asks for a sign-in answers 401 and names where (the grant is then found anew).
     */
    private Credential readCredential(McpConnection connection) {
        boolean signedIn = oauth.find(connection.id()).map(grant -> grant.accessToken() != null).orElse(false);
        return signedIn ? signedIn(connection) : Credential.key(key(connection));
    }

    /**
     * The software answered 401: whether it signs in with OAuth - its resource metadata (RFC 9728) named in the
     * header (only on its own host - a server must not send us elsewhere), else at the two well-known addresses. Found,
     * the grant is prepared with the authorization server it names: a result, not an error. 502 when that server
     * cannot be used.
     */
    private boolean signInFound(McpConnection connection, @Nullable String wwwAuthenticate) {
        Map<String, String> header = bearerParameters(wwwAuthenticate);
        URI address = URI.create(connection.baseUrl());
        String origin = address.getScheme() + "://" + address.getRawAuthority();
        String path = address.getRawPath() == null ? "" : address.getRawPath();
        List<String> candidates = new ArrayList<>();
        String named = header.get("resource_metadata");
        if (named != null && sameOrigin(named, connection.baseUrl())) {
            candidates.add(named);
        }
        if (!path.isEmpty() && !path.equals("/")) {
            candidates.add(origin + "/.well-known/oauth-protected-resource" + path);
        }
        candidates.add(origin + "/.well-known/oauth-protected-resource");
        for (String candidate : candidates) {
            OAuthMetadata.ProtectedResource resource = protectedResource(candidate, connection.name());
            if (resource != null) {
                AuthorizationServer server = oauthMetadata.read(resource.authorizationServer(), connection.name());
                String scopes = header.get("scope") != null ? header.get("scope") : resource.scopesSupported();
                oauth.prepare(connection.tenantId(), connection.id(), connection.baseUrl(), server, scopes);
                return true;
            }
        }
        return false;
    }

    private @Nullable OAuthMetadata.ProtectedResource protectedResource(String url, String connectionName) {
        try {
            return oauthMetadata.protectedResource(url, connectionName);
        } catch (ServiceException e) {
            return null;
        }
    }

    private static boolean sameOrigin(String url, String baseUrl) {
        try {
            return ModelServers.origin(url).equals(ModelServers.origin(baseUrl));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The parameters of a Bearer challenge: resource_metadata="...", scope="..." and the like. */
    static Map<String, String> bearerParameters(@Nullable String wwwAuthenticate) {
        Map<String, String> parameters = new HashMap<>();
        if (wwwAuthenticate == null) {
            return parameters;
        }
        Matcher matcher = CHALLENGE_PARAMETER.matcher(wwwAuthenticate);
        while (matcher.find()) {
            parameters.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
        }
        return parameters;
    }

    private static final Pattern CHALLENGE_PARAMETER = Pattern.compile("([A-Za-z_]+)=\"([^\"]*)\"");

    /**
     * An A2A agent's card (docs/architecture/a2a.md "The connection"): its name and description as the connection's
     * instructions, its skills as its tools - on at once (there is nothing to turn on: the agent is asked through one
     * tool, the skills name what it can), replaced on every reload. A card that cannot be read keeps the connection, as a
     * server whose tools cannot be read.
     */
    private McpConnectionResponse loadCard(McpConnection connection) {
        A2aClient.Card card;
        Credential reading;
        try {
            reading = readCredential(connection);
            card = a2a.card(connection, reading);
        } catch (ServiceException e) {
            return response(connection, e.getMessage(), null);
        }
        McpConnection loaded = transactions.executeWrite(_ -> {
            McpConnection current = connections.findByIdAndTenantIdForNoKeyUpdate(connection.id(),
                    connection.tenantId()).orElse(null);
            if (current == null || !current.baseUrl().equals(connection.baseUrl())) {
                return current;
            }
            if (card.signIn() == null || current.apiKey() != null) {
                oauth.find(current.id()).filter(grant -> grant.accessToken() == null)
                        .ifPresent(_ -> oauth.forget(current.id()));
            }
            return syncSkills(current, card);
        });
        // A key replaces the sign-in: a card naming both as alternatives keeps the key working - removing the key
        // offers the sign-in.
        String signInError = card.signIn() == null || reading.signedIn() || loaded == null || loaded.apiKey() != null
                ? null : signInOffered(loaded, card.signIn());
        return response(loaded != null ? loaded : connection, signInError, null);
    }

    /**
     * The card names an OAuth 2.0 scheme: the grant is prepared with its authorization server - read from the
     * metadata URL, else built from the authorization code flow's URLs (no registration there: the client id comes by
     * hand). The error to show when the scheme cannot be used; null when the sign-in is offered.
     */
    private @Nullable String signInOffered(McpConnection connection, A2aClient.SignInOffer offer) {
        try {
            AuthorizationServer server;
            if (offer.metadataUrl() != null) {
                server = oauthMetadata.readAt(offer.metadataUrl(), connection.name());
            } else if (offer.authorizationCode() && offer.authorizationUrl() != null && offer.tokenUrl() != null) {
                server = AuthorizationServer.ofEndpoints(offer.authorizationUrl(), offer.tokenUrl());
            } else {
                throw OAuthMetadata.noAuthorizationCode(connection.name());
            }
            oauth.prepare(connection.tenantId(), connection.id(), connection.baseUrl(), server, offer.scopes());
            return null;
        } catch (ServiceException e) {
            return e.getMessage();
        }
    }

    private McpConnection syncSkills(McpConnection connection, A2aClient.Card card) {
        Map<String, McpTool> known = new HashMap<>();
        for (McpTool tool : tools.findByConnectionIdOrderByName(connection.id())) {
            known.put(tool.name(), tool);
        }
        // As a software's tools: a skill once, within the limits of a listing - an agent's card is the tenant's choice.
        Set<String> kept = new HashSet<>();
        for (A2aClient.Skill skill : card.skills()) {
            if (skill.id().isBlank() || skill.id().length() > McpClient.MAX_TOOL_NAME
                    || kept.size() >= McpClient.MAX_TOOLS || !kept.add(skill.id())) {
                continue;
            }
            String description = McpClient.cut(skill.name() + ": " + skill.description(), McpClient.MAX_DESCRIPTION);
            McpTool tool = known.remove(skill.id());
            if (tool == null) {
                tools.save(new McpTool(null, connection.id(), skill.id(), description, "{}", false, true, true, true));
            } else {
                tools.update(new McpTool(tool.id(), tool.connectionId(), tool.name(), description, "{}", false, true,
                        true, true));
            }
        }
        known.values().forEach(dropped -> tools.delete(dropped));
        return connections.update(new McpConnection(connection.id(), connection.tenantId(), connection.name(),
                connection.baseUrl(), connection.apiKey(), connection.keyHint(), Instant.now(),
                connection.passesThrough(), keptInstructions(card.name() + "\n" + card.description()),
                connection.protocol(), connection.answersChat(), connection.createdAt(), Instant.now()));
    }

    /**
     * The tenant's A2A agent that answers every question of its chat (docs/architecture/a2a.md "A tenant answering
     * through its agent") - empty for an ordinary tenant.
     */
    public Optional<McpConnection> answeringAgent(Long tenantId) {
        return connections.findByTenantIdOrderByName(tenantId).stream().filter(McpConnection::answersChat).findFirst();
    }

    /** Whether the A2A agent answers the chat after this request: only an A2A agent may - 400 for another. */
    private static boolean answersChat(McpConnectionProtocol protocol, @Nullable Boolean requested, boolean current) {
        boolean answers = requested != null ? requested : current;
        if (answers && protocol != McpConnectionProtocol.A2A) {
            throw ServiceException.badRequest("mcpConnection.answersChatOnlyA2a",
                    "Only an A2A agent can answer the chat");
        }
        return answers;
    }

    /**
     * The check of an A2A agent: its card read and timed - empty for another tenant's connection. Not for MCP software
     * (409); the admin's switch off is a failed check, as the chat then answers.
     */
    public Optional<McpConnectionCheckResponse> check(Long tenantId, Long connectionId) {
        return connections.findByIdAndTenantId(connectionId, tenantId).map(connection -> {
            if (connection.protocol() != McpConnectionProtocol.A2A) {
                throw ServiceException.conflict("mcpConnection.checkOnlyA2a", "Only an A2A agent can be checked");
            }
            if (!tenants.findById(tenantId).map(tenant -> tenant.a2aAllowed()).orElse(false)) {
                return new McpConnectionCheckResponse(false, null, 0, 0,
                        "The tenant may not connect A2A agents - the admin allows it");
            }
            long started = System.nanoTime();
            try {
                A2aClient.Card card = a2a.card(connection, readCredential(connection));
                return new McpConnectionCheckResponse(true, card.name(), card.skills().size(),
                        (System.nanoTime() - started) / 1_000_000, null);
            } catch (ServiceException e) {
                return new McpConnectionCheckResponse(false, null, 0, (System.nanoTime() - started) / 1_000_000,
                        e.getMessage());
            }
        });
    }

    /** A skill of the agent as the chat's chips show it: its id, its name and its description. */
    public record Skill(String id, String name, String description) {
    }

    /**
     * The skills of the tenant's answering agent - the chips of the chat (package C); empty for a tenant answering
     * itself. The row's description is "name: description" as the card gave them.
     */
    public List<Skill> skillsOfAnsweringAgent(Long tenantId) {
        return answeringAgent(tenantId).map(agent -> tools.findByConnectionIdOrderByName(agent.id()).stream()
                .map(tool -> {
                    int colon = tool.description().indexOf(": ");
                    return colon < 0 ? new Skill(tool.name(), tool.name(), tool.description())
                            : new Skill(tool.name(), tool.description().substring(0, colon),
                            tool.description().substring(colon + 2));
                }).toList()).orElse(List.of());
    }

    /** The skills of an A2A agent's card as last read - their ids. */
    public List<String> skillsOf(McpConnection agent) {
        return tools.findByConnectionIdOrderByName(agent.id()).stream().map(McpTool::name).toList();
    }

    /** The tenant's connection of this name (case does not matter) - for an assistant prompt step of a task. */
    public Optional<McpConnection> connectionForTask(Long tenantId, String name) {
        return connections.findByTenantIdOrderByName(tenantId).stream()
                .filter(connection -> connection.name().equalsIgnoreCase(name.strip()))
                .findFirst();
    }

    /** Whether the tenant still has this connection - an assistant prompt step's software when it runs. */
    public boolean exists(Long tenantId, Long connectionId) {
        return connections.findByIdAndTenantId(connectionId, tenantId).isPresent();
    }

    /** The tenant's A2A agent of this name (case does not matter) - for a task's call_agent step. */
    public Optional<McpConnection> agentForTask(Long tenantId, String name) {
        return connections.findByTenantIdOrderByName(tenantId).stream()
                .filter(connection -> connection.protocol() == McpConnectionProtocol.A2A
                        && connection.name().equalsIgnoreCase(name.strip()))
                .findFirst();
    }

    /**
     * A task's agent when it calls: still there, still A2A, the tenant still allowed - 409 task.agentUnavailable
     * otherwise, which fails the run.
     */
    public McpConnection agentForRun(Long tenantId, Long connectionId) {
        return connections.findByIdAndTenantId(connectionId, tenantId)
                .filter(connection -> connection.protocol() == McpConnectionProtocol.A2A)
                .filter(_ -> a2aAllowed(tenantId))
                .orElseThrow(() -> ServiceException.conflict("task.agentUnavailable",
                        "The task's A2A agent is gone, or the tenant may no longer use A2A agents"));
    }

    /** One agent answers a tenant's chat - 409 when another connection does already. */
    private void requireNoOtherAnswering(Long tenantId, @Nullable Long connectionId) {
        answeringAgent(tenantId).filter(other -> !other.id().equals(connectionId)).ifPresent(other -> {
            throw ServiceException.conflict("mcpConnection.answersChatTaken", other.name()
                    + " answers the chat already - only one agent can", Map.of("name", other.name()));
        });
    }

    /** Whether the admin allows this tenant A2A agents. */
    public boolean a2aAllowed(Long tenantId) {
        return tenants.findById(tenantId).map(tenant -> tenant.a2aAllowed()).orElse(false);
    }

    /** The admin's switch: whose questions may leave the installation for an A2A agent. 409 when it is off. */
    private void requireA2aAllowed(Long tenantId) {
        if (!a2aAllowed(tenantId)) {
            throw ServiceException.conflict("mcpConnection.a2aNotAllowed",
                    "The tenant may not connect A2A agents - the admin allows it");
        }
    }

    /** Like the tools: new prompts come in off, dropped ones are kept but no longer offered, the admin's choice stays. */
    private void syncPrompts(McpConnection connection, List<McpClient.RemotePrompt> offered) {
        Map<String, McpPrompt> known = new HashMap<>();
        for (McpPrompt prompt : prompts.findByConnectionIdOrderByName(connection.id())) {
            known.put(prompt.name(), prompt);
        }
        for (McpClient.RemotePrompt remote : offered) {
            McpPrompt prompt = known.remove(remote.name());
            String arguments = write(remote.arguments().stream()
                    .map(argument -> new McpPromptArgument(argument.name(), argument.description(), argument.required()))
                    .toList());
            if (prompt == null) {
                prompts.save(new McpPrompt(null, connection.id(), remote.name(), remote.title(), remote.description(),
                        arguments, false, true));
            } else {
                prompts.update(new McpPrompt(prompt.id(), prompt.connectionId(), prompt.name(), remote.title(),
                        remote.description(), arguments, prompt.enabled(), true));
            }
        }
        for (McpPrompt dropped : known.values()) {
            prompts.update(new McpPrompt(dropped.id(), dropped.connectionId(), dropped.name(), dropped.title(),
                    dropped.description(), dropped.arguments(), dropped.enabled(), false));
        }
    }

    private McpConnection sync(McpConnection connection, List<McpClient.RemoteTool> offered, @Nullable String instructions) {
        Map<String, McpTool> known = new HashMap<>();
        for (McpTool tool : tools.findByConnectionIdOrderByName(connection.id())) {
            known.put(tool.name(), tool);
        }
        for (McpClient.RemoteTool remote : offered) {
            McpTool tool = known.remove(remote.name());
            String schema = write(remote.inputSchema());
            if (tool == null) {
                tools.save(new McpTool(null, connection.id(), remote.name(), remote.description(), schema,
                        !remote.readOnly(), remote.readOnly(), false, true));
            } else {
                tools.update(new McpTool(tool.id(), tool.connectionId(), tool.name(), remote.description(), schema,
                        changesAfterReload(tool, remote), remote.readOnly(), tool.enabled(), true));
            }
        }
        for (McpTool dropped : known.values()) {
            tools.update(new McpTool(dropped.id(), dropped.connectionId(), dropped.name(), dropped.description(),
                    dropped.inputSchema(), dropped.changes(), dropped.readOnlyHint(), dropped.enabled(), false));
        }
        return connections.update(new McpConnection(connection.id(), connection.tenantId(), connection.name(),
                connection.baseUrl(), connection.apiKey(), connection.keyHint(), Instant.now(),
                connection.passesThrough(), keptInstructions(instructions),
                connection.protocol(), connection.answersChat(), connection.createdAt(), Instant.now()));
    }

    /** The server's instructions as kept: stripped, cut to {@value #MAX_INSTRUCTIONS} characters; null for none. */
    static @Nullable String keptInstructions(@Nullable String instructions) {
        if (instructions == null || instructions.isBlank()) {
            return null;
        }
        String stripped = instructions.strip();
        return stripped.length() > MAX_INSTRUCTIONS ? stripped.substring(0, MAX_INSTRUCTIONS) : stripped;
    }

    /**
     * What the admin chose stays - unless the server changed its word: a software update that made a reading tool a
     * changing one (its readOnlyHint dropped) must not keep running unasked; one made reading takes it too.
     */
    private static boolean changesAfterReload(McpTool tool, McpClient.RemoteTool remote) {
        return remote.readOnly() == tool.readOnlyHint() ? tool.changes() : !remote.readOnly();
    }

    private McpConnectionResponse response(McpConnection connection, @Nullable String toolsError) {
        return response(connection, toolsError, null);
    }

    private McpConnectionResponse response(McpConnection connection, @Nullable String toolsError,
                                           @Nullable String promptsError) {
        return new McpConnectionResponse(connection.id(), connection.name(), connection.baseUrl(), connection.keyHint(),
                connection.toolsLoadedAt(), toolsError, promptsError, connection.passesThrough(), connection.instructions(),
                connection.protocol(), connection.answersChat(),
                tools.findByConnectionIdOrderByName(connection.id()).stream().map(McpToolResponse::from).toList(),
                prompts.findByConnectionIdOrderByName(connection.id()).stream()
                        .map(prompt -> McpPromptResponse.from(prompt, json)).toList(),
                oauth.find(connection.id()).map(OAuthClient::view).orElse(null));
    }

    private static String name(McpConnectionRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ServiceException.badRequest("mcpConnection.nameRequired", "The name is required");
        }
        String name = request.name().strip();
        if (name.length() > MAX_NAME) {
            throw ServiceException.badRequest("mcpConnection.nameTooLong", "The name may have at most " + MAX_NAME
                    + " characters",
                    Map.of("max", String.valueOf(MAX_NAME)));
        }
        return name;
    }

    private static String checkedKey(String key) {
        if (key.length() > MAX_KEY_LENGTH || key.chars().anyMatch(Character::isWhitespace)) {
            throw ServiceException.badRequest("key.notApiKey", "This is not an API key");
        }
        return key;
    }

    /** A short key would give away too much of itself in its hint. */
    private static String hint(String key) {
        return key.length() >= 12 ? "…" + key.substring(key.length() - 4) : "…";
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void requireTenant(Long tenantId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw ServiceException.notFound("tenant.notFound", "Tenant not found");
        }
    }
}
