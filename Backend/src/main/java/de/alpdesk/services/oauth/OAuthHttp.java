package de.alpdesk.services.oauth;

import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import de.alpdesk.services.ai.ModelServers;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.mcp.McpAddresses;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Every HTTP call of a sign-in (docs/architecture/mcp.md "Signing in to the software's server"): Nimbus builds and
 * parses the OAuth messages, this class sends them - its own send() would follow redirects and know nothing of our
 * address rules. Each address passes the rules of the software's own address first, a redirect is never followed, an
 * answer of more than {@value #MAX_BODY} bytes is not taken, and a failure names the connection, never an address.
 * The server's sign-in page, which only the browser opens, is checked here too ({@link #signInPage}).
 */
@Singleton
class OAuthHttp {

    static final int MAX_BODY = 64 * 1024;
    /**
     * For an answer's headers and body together. Not app.ai.timeout (minutes): a refresh holds its grant's row lock
     * and a database connection while it waits, and the database ends a transaction idle for a minute.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Headers the JDK client sets itself and refuses to take. */
    private static final Set<String> RESTRICTED = Set.of("content-length", "host", "connection", "expect", "upgrade");

    private final McpAddresses addresses;
    private final ModelServers servers;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    OAuthHttp(McpAddresses addresses, ModelServers servers) {
        this(addresses, servers, REQUEST_TIMEOUT);
    }

    /** With a deadline of its own - for the spec of an answer that stalls. */
    OAuthHttp(McpAddresses addresses, ModelServers servers, Duration timeout) {
        this.addresses = addresses;
        this.servers = servers;
        this.timeout = timeout;
    }

    /** An answer: its status, its content type and its body (cut at {@value #MAX_BODY} bytes). */
    record Answer(int status, @Nullable String contentType, String body) {
    }

    Answer get(String url, String connectionName) {
        URI uri = webAddress(url, connectionName);
        return send(HttpRequest.newBuilder(uri).GET().header("Accept", "application/json"), url, connectionName);
    }

    /**
     * The server's sign-in page - the one address of the flow we never call: the browser is sent there. A web address
     * only (a javascript: one would run in the installation's own page), over HTTPS unless the installation names its
     * host. Its name is not resolved: a page only the browser reaches is no way into our network, and behind a proxy
     * the server may not know the name at all.
     */
    URI signInPage(String url, String connectionName) {
        URI uri = webAddress(url, connectionName);
        servers.requireAllowed(url, connectionName);
        return uri;
    }

    /** A request Nimbus built, sent by us; the answer handed back to Nimbus to parse. */
    HTTPResponse send(HTTPRequest request, String connectionName) {
        URI uri = webAddress(request.getURL().toString(), connectionName);
        String body = request.getBody();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .method(request.getMethod().name(), body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        for (Map.Entry<String, List<String>> header : request.getHeaderMap().entrySet()) {
            if (!RESTRICTED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                for (String value : header.getValue()) {
                    builder.header(header.getKey(), value);
                }
            }
        }
        Answer answer = send(builder, uri.toString(), connectionName);
        HTTPResponse response = new HTTPResponse(answer.status());
        try {
            if (answer.contentType() != null) {
                response.setContentType(answer.contentType());
            }
        } catch (ParseException e) {
            // A content type Nimbus cannot read - parsing the body then fails with its own message.
        }
        response.setBody(answer.body());
        return response;
    }

    private Answer send(HttpRequest.Builder builder, String url, String connectionName) {
        addresses.requireAllowed(url, connectionName);
        // The deadline covers the body too: a request's own timeout ends with the headers.
        CompletableFuture<HttpResponse<byte[]>> answer = http.sendAsync(builder.build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BODY));
        try {
            HttpResponse<byte[]> response = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Answer(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
                    new String(response.body(), StandardCharsets.UTF_8));
        } catch (TimeoutException e) {
            answer.cancel(true);
            throw gateway("oauth.timeout", connectionName, "its sign-in server did not answer in time");
        } catch (InterruptedException e) {
            answer.cancel(true);
            Thread.currentThread().interrupt();
            throw gateway("oauth.timeout", connectionName, "its sign-in server did not answer in time");
        } catch (ExecutionException e) {
            // Not connected, the connection lost, or an answer larger than we take.
            throw gateway("oauth.unreachable", connectionName, "its sign-in server is not reachable");
        }
    }

    /** An absolute http(s) address with a host and without credentials - 502 for anything else a server names. */
    private static URI webAddress(String url, String connectionName) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getHost() != null && uri.getRawUserInfo() == null
                    && (scheme.equals("https") || scheme.equals("http"))) {
                return uri;
            }
        } catch (URISyntaxException e) {
            // Refused below.
        }
        throw gateway("oauth.metadataUnreadable", connectionName, "its sign-in server names an address that is none");
    }

    static ServiceException gateway(String code, String connectionName, String what) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, code, connectionName + ": " + what,
                Map.of("name", connectionName));
    }
}
