package de.alpdesk.services.filestore;

import de.alpdesk.services.common.InternalAddresses;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The hosts of the tenants' file stores. A tenant admin enters them and our server connects to them - so a host must
 * not lead into our own network (loopback, private, link-local - e.g. the cloud's metadata service) unless the
 * installation names it in app.file-store.internal-hosts (APP_FILE_STORE_INTERNAL_HOSTS): a store in the company's
 * network is common, but which one is the operator's decision. Checked when a store is saved and again before every
 * connection, where the name is resolved anew. The same list is the only way to plain http for an S3 endpoint: such a
 * store in the company's network often has no certificate.
 */
@Singleton
public class FileStoreAddresses {

    private static final Pattern HOST_NAME = Pattern.compile(
            "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-f:.]+");
    private static final int MAX_HOST = 253;

    private final Set<String> internalHosts;

    public FileStoreAddresses(@Value("${app.file-store.internal-hosts:}") String internalHosts) {
        this.internalHosts = Arrays.stream(internalHosts.split(","))
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The host as stored (lower case) - 400 for one that is not a host name or not allowed. */
    public String normalize(String host) {
        if (host == null || host.isBlank()) {
            throw ServiceException.badRequest("fileStore.hostRequired", "The host is required");
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_HOST || !(HOST_NAME.matcher(normalized).matches() || isIpv6(normalized))) {
            throw ServiceException.badRequest("fileStore.hostInvalid", "The host is a name or an IP address only - "
                    + "no protocol, port, user or path");
        }
        requireAllowed(normalized, HttpStatus.BAD_REQUEST);
        return normalized;
    }

    /**
     * An S3 store's endpoint as stored ("https://host[:port]") - null for none (Amazon S3). 400 for one that is not an
     * address of a server only, plain http to a host not in app.file-store.internal-hosts, or a host not allowed.
     */
    public @Nullable String normalizeEndpoint(@Nullable String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(endpoint.strip());
        } catch (URISyntaxException e) {
            throw endpointInvalid();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = hostOf(uri);
        boolean serverOnly = uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"));
        boolean validHost = host != null && host.length() <= MAX_HOST
                && (HOST_NAME.matcher(host).matches() || isIpv6(host));
        if (!(scheme.equals("https") || scheme.equals("http")) || !serverOnly || !validHost) {
            throw endpointInvalid();
        }
        requirePlainHttpAllowed(scheme, host, HttpStatus.BAD_REQUEST);
        requireAllowed(host, HttpStatus.BAD_REQUEST);
        String shownHost = host.contains(":") ? "[" + host + "]" : host;
        return scheme + "://" + shownHost + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    /**
     * Before every S3 session: the endpoint's host may resolve to another address since it was saved, or have been
     * taken off app.file-store.internal-hosts. 502 when it is no longer allowed.
     */
    public void requireAllowedEndpoint(URI endpoint) {
        String host = hostOf(endpoint);
        requirePlainHttpAllowed(endpoint.getScheme(), host, HttpStatus.BAD_GATEWAY);
        requireAllowed(host, HttpStatus.BAD_GATEWAY);
    }

    private void requirePlainHttpAllowed(String scheme, String host, HttpStatus status) {
        if (scheme.equals("http") && !internalHosts.contains(host)) {
            throw new ServiceException(status, "fileStore.plainHttp", "Plain http goes only to the hosts in "
                    + "APP_FILE_STORE_INTERNAL_HOSTS - use https", Map.of("host", host));
        }
    }

    /** The URI's host in lower case, without the brackets of an IPv6 address; null for none. */
    private static @Nullable String hostOf(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static ServiceException endpointInvalid() {
        return ServiceException.badRequest("fileStore.endpointInvalid", "The endpoint is the address of a server "
                + "only, like https://s3.example.com - no user, path or query");
    }

    /** Whether unencrypted connections may go to the host - one the operator named in app.file-store.internal-hosts. */
    public boolean plainAllowed(String host) {
        return internalHosts.contains(host);
    }

    /**
     * Before every connection: the host may resolve to another address since it was saved. Answers the address to
     * connect to, so a second lookup cannot lead elsewhere; 502 when it is not allowed.
     */
    public InetAddress resolveAllowed(String host) {
        return requireAllowed(host, HttpStatus.BAD_GATEWAY);
    }

    private InetAddress requireAllowed(String host, HttpStatus status) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (!internalHosts.contains(host)) {
                for (InetAddress address : addresses) {
                    if (InternalAddresses.isInternal(address)) {
                        throw new ServiceException(status, "fileStore.internalNetwork", "The host " + host + " lies in an "
                                + "internal network - only the hosts in APP_FILE_STORE_INTERNAL_HOSTS may",
                                Map.of("host", host));
                    }
                }
            }
            return addresses[0];
        } catch (UnknownHostException e) {
            throw new ServiceException(status, "fileStore.unknownHost", "The host " + host + " is not known",
                    Map.of("host", host));
        }
    }

    private static boolean isIpv6(String host) {
        return host.contains(":") && IPV6.matcher(host).matches();
    }
}
