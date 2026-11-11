package de.alpdesk.services.mcp;

import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches a file a connected software's tool returned as an {@code https} link (MCP {@code resource_link},
 * docs/architecture/mcp.md "Files in tool results") - a temporary download, valid for minutes, so it is fetched at
 * once. The address is the software's, not the tenant admin's: it passes the connections' address rules
 * ({@link McpAddresses}) before the request and after every redirect, which is followed by hand. Our bearer goes only
 * to the connection's own origin - a download host elsewhere (a storage service the link was signed for) never sees
 * it; the link carries its own right.
 */
@Singleton
public class LinkDownloads {

    /** A linked file at most - a chat file's limit. */
    static final int MAX_BYTES = ChatFileService.MAX_FILE_BYTES;
    /** The whole download at most, headers and body. */
    static final Duration MAX_TIME = Duration.ofSeconds(60);
    static final int MAX_REDIRECTS = 3;

    /** {@link #MAX_TIME}; a spec shortens it, so a slow server is tried without waiting a minute. */
    Duration maxTime = MAX_TIME;

    private final McpAddresses addresses;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LinkDownloads(McpAddresses addresses) {
        this.addresses = addresses;
    }

    /** The file and the type the server sent it with (null when none). */
    public record Download(byte[] content, @Nullable String contentType) {
    }

    /**
     * Why a link gave no file - in words for the model; {@code forNow} when the next try may give it (the server out of
     * reach, too slow, failing), not when the link itself is the matter (expired, refused, not allowed).
     */
    public static final class NotFetched extends Exception {

        private final boolean forNow;

        NotFetched(String reason) {
            this(reason, false);
        }

        NotFetched(String reason, boolean forNow) {
            super(reason, null, false, false);
            this.forNow = forNow;
        }

        public boolean forNow() {
            return forNow;
        }
    }

    /**
     * The file behind the link. The bearer (null: none) is sent only while the address is on the connection's origin.
     */
    public Download fetch(String connectionName, String connectionUrl, String link, @Nullable String bearer)
            throws NotFetched {
        long deadline = System.nanoTime() + maxTime.toNanos();
        URI address = parse(link, "the link is no address to fetch");
        for (int redirects = 0; ; redirects++) {
            requireAllowed(connectionName, address);
            HttpResponse<byte[]> response = send(address, sameOrigin(address, URI.create(connectionUrl)) ? bearer : null,
                    deadline);
            int status = response.statusCode();
            if (status >= 300 && status < 400 && response.headers().firstValue("Location").isPresent()) {
                if (redirects >= MAX_REDIRECTS) {
                    throw new NotFetched("more than " + MAX_REDIRECTS + " redirects");
                }
                address = address.resolve(parse(response.headers().firstValue("Location").get(),
                        "it was sent on to no valid address"));
                continue;
            }
            if (status == 404 || status == 410) {
                throw new NotFetched("the link has expired or the file is gone (HTTP " + status + ")");
            }
            if (status != 200) {
                throw new NotFetched("the server answered HTTP " + status, status >= 500 || status == 429);
            }
            return new Download(response.body(), contentType(response));
        }
    }

    /** A software's link or redirect as it came - a space or a bracket in it is no address (java.net.URI). */
    private static URI parse(String address, String reason) throws NotFetched {
        try {
            return new URI(address);
        } catch (URISyntaxException e) {
            throw new NotFetched(reason);
        }
    }

    private void requireAllowed(String connectionName, URI address) throws NotFetched {
        String scheme = address.getScheme() == null ? "" : address.getScheme().toLowerCase(Locale.ROOT);
        if (address.getHost() == null || address.getRawUserInfo() != null
                || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new NotFetched("the link is no address to fetch");
        }
        try {
            addresses.requireAllowed(address.toString(), connectionName);
        } catch (ServiceException e) {
            throw new NotFetched("its address is not allowed - " + e.getMessage());
        }
    }

    private HttpResponse<byte[]> send(URI address, @Nullable String bearer, long deadline) throws NotFetched {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw tooSlow();
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(address).GET().timeout(Duration.ofNanos(left));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        // The deadline covers the body too: the request's timeout ends with the headers.
        CompletableFuture<HttpResponse<byte[]>> answer = http.sendAsync(request.build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BYTES));
        try {
            return answer.get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            answer.cancel(true);
            throw tooSlow();
        } catch (InterruptedException e) {
            answer.cancel(true);
            Thread.currentThread().interrupt();
            throw new NotFetched("the download was interrupted", true);
        } catch (ExecutionException e) {
            throw notFetched(e.getCause());
        }
    }

    private static NotFetched tooSlow() {
        return new NotFetched("it took longer than " + MAX_TIME.toSeconds() + " seconds", true);
    }

    private static NotFetched notFetched(Throwable cause) {
        if (cause instanceof IOException && String.valueOf(cause.getMessage()).contains("body exceeds capacity")) {
            return new NotFetched("the file is larger than " + MAX_BYTES / 1024 / 1024 + " MB");
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return tooSlow();
        }
        return new NotFetched("the server could not be reached", true);
    }

    private static @Nullable String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
                .map(type -> type.split(";")[0].strip())
                .filter(type -> !type.isEmpty())
                .orElse(null);
    }

    /** Scheme, host and port, the scheme's default port filled in. */
    static boolean sameOrigin(URI a, URI b) {
        return scheme(a).equals(scheme(b)) && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }

    private static String scheme(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return scheme(uri).equals("https") ? 443 : 80;
    }
}
