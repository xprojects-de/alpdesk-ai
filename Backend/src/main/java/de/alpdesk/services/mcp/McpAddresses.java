package de.alpdesk.services.mcp;

import de.alpdesk.services.ai.ModelServers;
import de.alpdesk.services.common.InternalAddresses;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * The addresses of the tenants' software (docs/architecture/mcp.md "MCP clients"). Unlike the model servers a tenant
 * admin enters them - and our server calls them: so besides the model servers' rules (HTTPS, plain HTTP only to the
 * hosts in app.ai.plain-http-hosts, no credentials in the address) an address must not lead into our own network
 * (loopback, private, link-local - e.g. the cloud's metadata service) unless the installation names its host there.
 * Checked when it is saved and again before every call, where its name is resolved anew.
 */
@Singleton
public class McpAddresses {

    private final ModelServers servers;

    public McpAddresses(ModelServers servers) {
        this.servers = servers;
    }

    /** The address as stored - 400 for one that is not allowed. */
    public String normalize(String baseUrl) {
        String address = servers.normalize(baseUrl);
        Refusal refusal = internalRefusal(URI.create(address).getHost());
        if (refusal != null) {
            throw ServiceException.badRequest(refusal.code(), refusal.message(), refusal.params());
        }
        return address;
    }

    /** Before every call: the host may resolve to another address since it was saved. 502 when it is not allowed. */
    public void requireAllowed(String baseUrl, String name) {
        servers.requireAllowed(baseUrl, name);
        Refusal refusal = internalRefusal(URI.create(baseUrl).getHost());
        if (refusal != null) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, refusal.codeAtCall(), name + ": " + refusal.messageAtCall(),
                    Map.of("name", name));
        }
    }

    /**
     * Why a host may not be called: when the address is saved - to the admin who typed it - and when it is called later.
     * Then the refusal reaches the chat's model and user and an MCP client, so it names the connection, never the
     * address (docs/architecture/mcp.md).
     */
    private record Refusal(String code, String codeAtCall, String message, String messageAtCall, Map<String, String> params) {
    }

    /** Why the host may not be called; null when it may. */
    private Refusal internalRefusal(String host) {
        if (servers.isPlainHttpHost(host)) {
            return null;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (InternalAddresses.isInternal(address)) {
                    String internal = "The address leads into an internal network - only the hosts in "
                            + "APP_AI_PLAIN_HTTP_HOSTS may";
                    return new Refusal("mcpAddress.internalNetwork", "mcpServer.internalNetwork", internal, internal,
                            Map.of());
                }
            }
            return null;
        } catch (UnknownHostException e) {
            return new Refusal("mcpAddress.unknownHost", "mcpServer.unknownHost", "The host " + host + " is not known",
                    "The host of its address is not known", Map.of("host", host));
        }
    }
}
