package de.alpdesk.services.mcp;

import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.entities.mcp.McpConnectionProtocol;
import de.alpdesk.repositories.mcp.McpConnectionRepository;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The resources of the connected software passed through to MCP clients (docs/architecture/mcp.md "Resources of the
 * software", decided 11 November 2026): where a connection passes its resources on ("Ressourcen an MCP-Clients") a key
 * that passes the software through lists and reads them. A client reads a resource by its address, and several
 * servers lie behind one: so the connection goes in front of the address, separated by a dot -
 * `ticket://4711/protokoll.log` becomes `ticketsystem.ticket://4711/protokoll.log`. Reading it, the prefix is taken off
 * and exactly that software asked with the address as it was - nothing guessed, nothing stored. A web address is never
 * prefixed: a client may fetch it directly.
 * <p>
 * The lists are asked live, every time - resources change with every ticket. A page of ours is a page of one software;
 * our cursor names the software and carries its cursor.
 */
@Singleton
public class SoftwareResources {

    private static final Logger LOG = LoggerFactory.getLogger(SoftwareResources.class);

    private final SoftwareMcpProxy proxy;
    private final McpConnectionRepository connections;
    private final McpConnectionService connectionService;
    private final McpClient client;

    public SoftwareResources(SoftwareMcpProxy proxy, McpConnectionRepository connections,
                             McpConnectionService connectionService, McpClient client) {
        this.proxy = proxy;
        this.connections = connections;
        this.connectionService = connectionService;
        this.client = client;
    }

    /** A request we refuse with a JSON-RPC error - an unknown address, a cursor that is not ours. */
    public static final class Refused extends RuntimeException {

        private final transient McpSchema.JSONRPCResponse.JSONRPCError error;

        Refused(int code, String message) {
            super(message);
            this.error = new McpSchema.JSONRPCResponse.JSONRPCError(code, message, null);
        }

        Refused(McpSchema.JSONRPCResponse.JSONRPCError error) {
            super(error.message());
            this.error = error;
        }

        public McpSchema.JSONRPCResponse.JSONRPCError error() {
            return error;
        }
    }

    /** Whether the key reads resources of any software - initialize then says the server has resources. */
    public boolean offered(Long tenantId, Long mcpKeyId) {
        return !sources(tenantId, mcpKeyId).isEmpty();
    }

    /** The key's software that passes through and passes its resources on, in the order of their names. */
    private List<McpConnection> sources(Long tenantId, Long mcpKeyId) {
        return proxy.connectionsOf(tenantId, mcpKeyId).stream().filter(McpConnection::passesResources).toList();
    }

    /**
     * The key's sources for a resources request - "method not found" without any, as the SDK answers it: initialize
     * said the server has no resources.
     */
    private List<McpConnection> sourcesFor(Long tenantId, Long mcpKeyId, String method) {
        List<McpConnection> sources = sources(tenantId, mcpKeyId);
        if (sources.isEmpty()) {
            throw new Refused(McpSchema.ErrorCodes.METHOD_NOT_FOUND, "Method not found: " + method);
        }
        return sources;
    }

    /** A page of the key's resources: one page of one software, the addresses with the connection in front. */
    public McpSchema.ListResourcesResult list(Long tenantId, Long mcpKeyId, @Nullable String cursor) {
        List<McpConnection> sources = sourcesFor(tenantId, mcpKeyId, McpSchema.METHOD_RESOURCES_LIST);
        Page<McpSchema.Resource> page = page(tenantId, sources, cursor, (connection, softwareCursor) -> {
            McpSchema.ListResourcesResult listed = client.listResources(connection.tenantId(), connection.name(),
                    connection.baseUrl(), connectionService.credential(connection), softwareCursor);
            return new SoftwarePage<>(listed.resources(), listed.nextCursor());
        }, SoftwareResources::listed);
        return new McpSchema.ListResourcesResult(page.items(), page.nextCursor());
    }

    /** A page of the key's resource templates, as {@link #list}. */
    public McpSchema.ListResourceTemplatesResult templates(Long tenantId, Long mcpKeyId, @Nullable String cursor) {
        List<McpConnection> sources = sourcesFor(tenantId, mcpKeyId, McpSchema.METHOD_RESOURCES_TEMPLATES_LIST);
        Page<McpSchema.ResourceTemplate> page = page(tenantId, sources, cursor, (connection, softwareCursor) -> {
            McpSchema.ListResourceTemplatesResult listed = client.listResourceTemplates(connection.tenantId(),
                    connection.name(), connection.baseUrl(), connectionService.credential(connection), softwareCursor);
            return new SoftwarePage<>(listed.resourceTemplates(), listed.nextCursor());
        }, SoftwareResources::listed);
        return new McpSchema.ListResourceTemplatesResult(page.items(), page.nextCursor());
    }

    /**
     * Reads the resource at the software its address names: the answer as the software gave it, its addresses with
     * the connection in front again. "Resource not found" for a web address, an address without the prefix of one of
     * the key's software - saying how the addresses look, so a model that took one out of a text finds its way - and
     * for the software's own "not found", which goes back as the software answered.
     */
    public McpSchema.ReadResourceResult read(Long tenantId, Long mcpKeyId, @Nullable String uri) {
        List<McpConnection> sources = sourcesFor(tenantId, mcpKeyId, McpSchema.METHOD_RESOURCES_READ);
        if (uri == null || uri.isBlank()) {
            throw new Refused(McpSchema.ErrorCodes.INVALID_PARAMS, "The resource's uri is required");
        }
        if (McpClient.isWebAddress(uri)) {
            throw new Refused(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, "Resource not found: " + uri
                    + " - a web address is not read through this server; fetch it directly");
        }
        Map<Long, String> parts = parts(connections.findByTenantIdOrderByName(tenantId));
        int dot = uri.indexOf('.');
        String part = dot > 0 ? uri.substring(0, dot) : null;
        McpConnection connection = sources.stream()
                .filter(source -> parts.get(source.id()).equals(part)).findFirst()
                .orElseThrow(() -> new Refused(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, "Resource not found: " + uri
                        + " - the addresses of connected software start with \"<connection>.\"; list the resources "
                        + "(resources/list) to find them"));
        McpClient.ReadAnswer answer;
        try {
            answer = client.readResource(connection.tenantId(), connection.name(), connection.baseUrl(),
                    connectionService.credential(connection), uri.substring(dot + 1));
        } catch (ServiceException e) {
            throw new Refused(McpSchema.ErrorCodes.INTERNAL_ERROR, e.getMessage());
        }
        if (answer.error() != null) {
            throw new Refused(answer.error());
        }
        List<McpSchema.ResourceContents> contents = new ArrayList<>();
        for (McpSchema.ResourceContents read : answer.result().contents()) {
            contents.add(prefixed(part, read));
        }
        return new McpSchema.ReadResourceResult(contents, SoftwareMcpProxy.passedMeta(answer.result().meta()));
    }

    /** One page of one software, with the next page's address in our cursor. */
    private record Page<T>(List<T> items, @Nullable String nextCursor) {
    }

    /** A page as the software sent it. */
    private record SoftwarePage<T>(List<T> items, @Nullable String nextCursor) {
    }

    @FunctionalInterface
    private interface PageReader<T> {
        SoftwarePage<T> read(McpConnection connection, @Nullable String cursor);
    }

    @FunctionalInterface
    private interface Prefixer<T> {
        T prefixed(T item, McpConnection connection, String part);
    }

    /**
     * The page the cursor names - none: the first software's first page. A software that fails, or has nothing on its
     * page and no next one, is left out and the next one asked in the same request: a page with entries, or the end.
     */
    private <T> Page<T> page(Long tenantId, List<McpConnection> sources, @Nullable String cursor, PageReader<T> reader,
                             Prefixer<T> prefixer) {
        Cursor at = cursor == null ? null : Cursor.decode(cursor);
        int start = 0;
        if (at != null) {
            start = indexOf(sources, at.connectionId());
            if (start < 0) {
                throw new Refused(McpSchema.ErrorCodes.INVALID_PARAMS, "Invalid cursor - list again from the start");
            }
        }
        Map<Long, String> parts = parts(connections.findByTenantIdOrderByName(tenantId));
        for (int index = start; index < sources.size(); index++) {
            McpConnection connection = sources.get(index);
            String softwareCursor = index == start && at != null ? at.softwareCursor() : null;
            SoftwarePage<T> page;
            try {
                page = reader.read(connection, softwareCursor);
            } catch (ServiceException e) {
                LOG.debug("The resources of {} of tenant {} are left out: {}", SoftwareMcpProxy.logged(connection.name()),
                        tenantId, e.code());
                continue;
            }
            List<T> items = new ArrayList<>();
            for (T item : page.items() == null ? List.<T>of() : page.items()) {
                items.add(prefixer.prefixed(item, connection, parts.get(connection.id())));
            }
            boolean morePages = page.nextCursor() != null && !page.nextCursor().isBlank();
            if (items.isEmpty() && !morePages) {
                continue;
            }
            String next = morePages ? new Cursor(connection.id(), page.nextCursor()).encode()
                    : index + 1 < sources.size() ? new Cursor(sources.get(index + 1).id(), null).encode() : null;
            return new Page<>(items, next);
        }
        return new Page<>(List.of(), null);
    }

    private static int indexOf(List<McpConnection> sources, long connectionId) {
        for (int index = 0; index < sources.size(); index++) {
            if (sources.get(index).id() == connectionId) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Our cursor: the software it continues with and that software's cursor (none: its first page) - opaque to the
     * client, as the specification asks.
     */
    private record Cursor(long connectionId, @Nullable String softwareCursor) {

        String encode() {
            String text = connectionId + "\n" + (softwareCursor == null ? "" : softwareCursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int newline = text.indexOf('\n');
                String softwareCursor = text.substring(newline + 1);
                return new Cursor(Long.parseLong(text.substring(0, newline)),
                        softwareCursor.isEmpty() ? null : softwareCursor);
            } catch (RuntimeException e) {
                throw new Refused(McpSchema.ErrorCodes.INVALID_PARAMS, "Invalid cursor - list again from the start");
            }
        }
    }

    private static McpSchema.Resource listed(McpSchema.Resource resource, McpConnection connection, String part) {
        return new McpSchema.Resource(prefixed(part, resource.uri()), named(connection, resource.name()),
                resource.title() == null ? null : named(connection, resource.title()), resource.description(),
                resource.mimeType(), resource.size(), resource.annotations(),
                SoftwareMcpProxy.passedMeta(resource.meta()), resource.icons());
    }

    private static McpSchema.ResourceTemplate listed(McpSchema.ResourceTemplate template, McpConnection connection,
                                                     String part) {
        return new McpSchema.ResourceTemplate(prefixed(part, template.uriTemplate()), named(connection, template.name()),
                template.title() == null ? null : named(connection, template.title()), template.description(),
                template.mimeType(), template.annotations(), SoftwareMcpProxy.passedMeta(template.meta()),
                template.icons());
    }

    /** The name a client shows, after the software it comes from - like a tool's description. */
    private static String named(McpConnection connection, String name) {
        return "[" + connection.name() + "] " + name;
    }

    private static McpSchema.ResourceContents prefixed(String part, McpSchema.ResourceContents contents) {
        return switch (contents) {
            case McpSchema.TextResourceContents text -> new McpSchema.TextResourceContents(
                    prefixed(part, text.uri()), text.mimeType(), text.text(), text.meta());
            case McpSchema.BlobResourceContents blob -> new McpSchema.BlobResourceContents(
                    prefixed(part, blob.uri()), blob.mimeType(), blob.blob(), blob.meta());
            default -> contents;
        };
    }

    /** The address with the connection's part in front - a web address (or none) as it is. */
    static @Nullable String prefixed(String part, @Nullable String uri) {
        return uri == null || McpClient.isWebAddress(uri) ? uri : part + "." + uri;
    }

    /**
     * Each MCP connection's part of an address, from all of the tenant's - so it does not depend on the key, nor on a
     * switch. Lower case, letters, digits and "-" only, starting with a letter (an address must) and without a dot, so
     * the first dot always splits: "Ticketsystem" - "ticketsystem", "Lager Süd" - "lager-sud". A name starting with a
     * digit, or without any such character, is "c" and the connection's id; parts that come out alike all get "-" and
     * their connection's id appended - an id has no "-", so these never meet another part.
     */
    static Map<Long, String> parts(List<McpConnection> tenantConnections) {
        Map<Long, String> parts = new HashMap<>();
        for (McpConnection connection : tenantConnections) {
            if (connection.protocol() == McpConnectionProtocol.MCP) {
                parts.put(connection.id(), part(connection.name(), connection.id()));
            }
        }
        boolean alike = true;
        while (alike) {
            alike = false;
            Map<String, Integer> counts = new HashMap<>();
            parts.values().forEach(part -> counts.merge(part, 1, Integer::sum));
            for (Map.Entry<Long, String> entry : parts.entrySet()) {
                String withId = "-" + entry.getKey();
                if (counts.get(entry.getValue()) > 1 && !entry.getValue().endsWith(withId)) {
                    entry.setValue(entry.getValue() + withId);
                    alike = true;
                }
            }
        }
        return parts;
    }

    private static String part(String name, long id) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return plain.isEmpty() || !Character.isLetter(plain.charAt(0)) ? "c" + id : plain;
    }
}
