package de.alpdesk.services.ai;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The addresses of own model servers (OLLAMA, OPENAI_COMPATIBLE, BEDROCK, TEI - docs/architecture/ai-features.md
 * "Own model servers"). Only the admin enters them. A server's key and the company's data go there, so the address must be
 * HTTPS - plain HTTP only to the hosts the installation names in app.ai.plain-http-hosts (a server in the same pod or
 * the internal network), never by a setting in the frontend.
 */
@Singleton
public class ModelServers {

    private static final int MAX_LENGTH = 500;

    private final Set<String> plainHttpHosts;

    public ModelServers(@Value("${app.ai.plain-http-hosts:}") String plainHttpHosts) {
        this.plainHttpHosts = Arrays.stream(plainHttpHosts.split(","))
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The address as stored, without a trailing slash - 400 unless it is an absolute https URL (http for an allowed
     * host) without user info, query or fragment: credentials in the address would show in the catalog and the logs.
     */
    public String normalize(String baseUrl) {
        String address = baseUrl == null ? "" : baseUrl.strip();
        while (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }
        if (address.isEmpty() || address.length() > MAX_LENGTH) {
            throw ServiceException.badRequest("modelServer.addressRequired", "The server address is required (at most "
                    + MAX_LENGTH + " characters)",
                    Map.of("max", String.valueOf(MAX_LENGTH)));
        }
        URI uri = parse(address);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || hasEmptyPathPart(uri)) {
            throw ServiceException.badRequest("modelServer.addressForm",
                    "The server address must be like https://host[:port][/path], without user, query or fragment");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw ServiceException.badRequest("modelServer.httpsRequired",
                    "The server address must start with https://");
        }
        if (scheme.equals("http") && !isPlainHttpHost(uri.getHost())) {
            throw ServiceException.badRequest("modelServer.plainHttpNotAllowed",
                    "The server must be reached over HTTPS - plain HTTP only to the hosts in "
                    + "APP_AI_PLAIN_HTTP_HOSTS");
        }
        return address;
    }

    /**
     * Checked again before every call: a host taken out of app.ai.plain-http-hosts no longer gets the key and the data
     * over plain HTTP, although its catalog entry was saved before.
     */
    public void requireAllowed(String baseUrl, String modelName) {
        URI uri = parse(baseUrl);
        if (!uri.getScheme().equalsIgnoreCase("https") && !isPlainHttpHost(uri.getHost())) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "modelServer.plainHttpRefused", "The server of "
                    + modelName + " may not be reached over plain HTTP - ask the admin",
                    Map.of("model", modelName));
        }
    }

    /** Scheme, host and port - a server's key belongs to them: another one never gets it. */
    public static String origin(String baseUrl) {
        URI uri = URI.create(baseUrl);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + uri.getPort();
    }

    /** Whether the installation names the host in app.ai.plain-http-hosts - a server it runs itself or trusts. */
    public boolean isPlainHttpHost(String host) {
        return plainHttpHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * A path like //other.host/mcp: resolved against the server's address it names another host, which no check here
     * has seen - so an empty part is no path of a server.
     */
    private static boolean hasEmptyPathPart(URI uri) {
        return uri.getRawPath() != null && uri.getRawPath().contains("//");
    }

    private static URI parse(String address) {
        try {
            URI uri = new URI(address);
            if (!uri.isAbsolute()) {
                throw ServiceException.badRequest("modelServer.httpsRequired",
                        "The server address must start with https://");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw ServiceException.badRequest("modelServer.addressInvalid", "The server address is not a valid URL");
        }
    }
}
