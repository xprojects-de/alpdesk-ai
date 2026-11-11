package de.alpdesk.services.filestore;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.MsalServiceException;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SharePoint stores (docs/architecture/file-stores.md "SharePoint and OneDrive"): a document library of a SharePoint
 * site or a OneDrive, listed and read through Microsoft Graph with the token of the customer's app registration (client
 * credentials - no person signs in). The token comes from Microsoft's own library (MSAL); the Graph calls are a
 * handful, so no Graph SDK: the JDK's HttpURLConnection, the one client with a timeout per read - a download hanging
 * halfway must not hold a task thread.
 */
@Singleton
public class GraphStoreClient {

    private static final int CONNECT_TIMEOUT = (int) Duration.ofSeconds(10).toMillis();
    private static final int READ_TIMEOUT = (int) Duration.ofSeconds(30).toMillis();
    /** The longest wait Graph may ask for when it throttles - a longer one, and the store counts as throttled. */
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    /** The wait when a throttling answer names none. */
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);
    /** A JSON answer read at most - a page of 999 entries is far below. */
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final Set<String> SCOPES = Set.of("https://graph.microsoft.com/.default");
    /** Entra's error number in a refusal's text ("AADSTS7000215: Invalid client secret ..."). */
    private static final Pattern ENTRA_ERROR = Pattern.compile("AADSTS(\\d+)");
    private static final int TOKEN_TIMEOUT_SECONDS = 60;
    private static final String ITEM_FIELDS = "name,size,lastModifiedDateTime,file,folder";
    /** Entra's codes of an unknown directory (AADSTS90002, 900023 for one that is not even well-formed). */
    private static final Set<Integer> DIRECTORY_UNKNOWN = Set.of(90002, 900023);
    /** Entra's code of an expired client secret. */
    private static final int SECRET_EXPIRED = 7000222;

    private final GraphAddresses addresses;
    private final JsonMapper json;

    public GraphStoreClient(GraphAddresses addresses, JsonMapper json) {
        this.addresses = addresses;
        this.json = json;
    }

    /** Logs in, finds the site and its library and lists the store's folder - its first names. */
    public CheckResult check(GraphSettings settings, String secret) {
        try (Session session = session(settings, secret)) {
            List<String> names = new ArrayList<>();
            for (RemoteFile entry : session.list(settings.basePath(), FileStoreClient.CHECK_ENTRIES)) {
                names.add(entry.name());
            }
            return CheckResult.ok(null, names);
        }
    }

    /** A session for reading: logged in, the library found. */
    public FileStoreSession open(GraphSettings settings, String secret) {
        return session(settings, secret);
    }

    private Session session(GraphSettings settings, String secret) {
        Session session = new Session(settings, application(settings, secret));
        session.token = token(session, false);
        session.drive = drive(session);
        return session;
    }

    /**
     * The app as MSAL knows it, one per session (its token cache lives as long). The authority is Microsoft's fixed
     * login, so neither its validation nor the instance discovery has anything to add - both would be calls of their
     * own.
     */
    private ConfidentialClientApplication application(GraphSettings settings, String secret) {
        try {
            return ConfidentialClientApplication.builder(settings.clientId(), ClientCredentialFactory.createFromSecret(secret))
                    .authority(addresses.login() + "/" + encode(settings.directoryId()) + "/")
                    .validateAuthority(false)
                    .instanceDiscovery(false)
                    .sslSocketFactory(addresses.sslSocketFactory())
                    .connectTimeoutForDefaultHttpClient(CONNECT_TIMEOUT)
                    .readTimeoutForDefaultHttpClient(READ_TIMEOUT)
                    .build();
        } catch (IOException | IllegalArgumentException e) {
            return failWith("fileStore.clientRefused", "The app's settings are not usable: " + e.getMessage(),
                    Map.of());
        }
    }

    /**
     * The app's token (client credentials) - fresh: past MSAL's cache, for a token Graph refused as expired. A refusal
     * says what is wrong: the directory, the app or its secret.
     */
    private String token(Session session, boolean fresh) {
        ClientCredentialParameters parameters = ClientCredentialParameters.builder(SCOPES).skipCache(fresh).build();
        try {
            return session.application.acquireToken(parameters).get(TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .accessToken();
        } catch (ExecutionException e) {
            throw loginFailure(session.settings, e.getCause());
        } catch (TimeoutException e) {
            throw unreachable(addresses.login(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(addresses.login(), e);
        }
    }

    private ServiceException loginFailure(GraphSettings settings, Throwable cause) {
        if (cause instanceof MsalServiceException refused) {
            Matcher number = ENTRA_ERROR.matcher(String.valueOf(refused.getMessage()));
            int code = number.find() ? Integer.parseInt(number.group(1)) : -1;
            if (DIRECTORY_UNKNOWN.contains(code)) {
                return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.directoryNotFound", "Entra ID knows "
                        + "no directory " + settings.directoryId());
            }
            if (code == SECRET_EXPIRED) {
                return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.secretExpired", "The app's client "
                        + "secret has expired - create a new one");
            }
            return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.clientRefused", "Entra ID refused the app "
                    + "or its secret: " + refused.errorCode());
        }
        if (causedBy(cause, SSLException.class)) {
            return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.tlsFailed", "The TLS connection to "
                    + addresses.login().getHost() + " failed", Map.of("host", addresses.login().getHost()));
        }
        return unreachable(addresses.login(), cause);
    }

    /** The library's drive id - the site's default library when none is named. */
    private String drive(Session session) {
        GraphSettings settings = session.settings;
        URI site = URI.create(settings.siteUrl());
        String sitePath = site.getRawPath() == null || site.getRawPath().isEmpty() ? "" : ":" + site.getRawPath();
        Answer found = session.get(graph("/sites/" + site.getHost() + sitePath + "?$select=id"));
        if (found.status() == 404 || found.status() == 400) {
            failWith("fileStore.siteNotFound", "There is no site " + settings.siteUrl(),
                    Map.of("site", settings.siteUrl()));
        }
        requireAllowed(found, settings);
        String siteId = String.valueOf(found.json().get("id"));
        if (settings.library() == null) {
            return String.valueOf(requireOk(session.get(graph("/sites/" + siteId + "/drive?$select=id")), settings)
                    .json().get("id"));
        }
        Answer drives = requireOk(session.get(graph("/sites/" + siteId + "/drives?$select=id,name")), settings);
        for (Map<?, ?> drive : entries(drives, "/")) {
            if (settings.library().equalsIgnoreCase(String.valueOf(drive.get("name")))) {
                return String.valueOf(drive.get("id"));
            }
        }
        return failWith("fileStore.libraryNotFound", "The site has no library " + settings.library(),
                Map.of("library", settings.library()));
    }

    private final class Session implements FileStoreSession {

        private final GraphSettings settings;
        private final ConfidentialClientApplication application;
        private String token;
        private String drive;

        private Session(GraphSettings settings, ConfidentialClientApplication application) {
            this.settings = settings;
            this.application = application;
        }

        @Override
        public List<RemoteFile> list(String folder) {
            return list(folder, null);
        }

        /** The folder's entries - all of them, or the first ones only (a check). */
        private List<RemoteFile> list(String folder, @Nullable Integer first) {
            String top = first == null ? "999" : String.valueOf(first);
            URI page = graph(itemPath(folder) + (folder.equals("/") ? "/children" : ":/children")
                    + "?$select=" + ITEM_FIELDS + "&$top=" + top);
            List<RemoteFile> entries = new ArrayList<>();
            while (page != null) {
                Answer answer = get(page);
                if (answer.status() == 404) {
                    failWith("fileStore.folderNotFound", "The folder " + folder + " does not exist",
                            Map.of("path", folder));
                }
                requireOk(answer, settings);
                for (Map<?, ?> item : entries(answer, folder)) {
                    RemoteFile entry = entry(item);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
                page = first == null ? nextPage(answer) : null;
            }
            entries.sort(Comparator.comparing(RemoteFile::name));
            return first == null ? entries : entries.subList(0, Math.min(first, entries.size()));
        }

        /**
         * At most maxBytes from offset on, through the download address Graph hands out for the file - asked for as a
         * range, without the token. Microsoft may send the whole file instead (200, not 206): it is read up to the
         * offset and cut off after maxBytes.
         */
        @Override
        public byte[] read(String path, long offset, int maxBytes) {
            if (maxBytes <= 0) {
                return new byte[0];
            }
            Answer item = get(graph(itemPath(path)));
            if (item.status() == 404) {
                failWith("fileStore.readFailed", "The file " + path + " is gone", Map.of("path", path));
            }
            requireOk(item, settings);
            Object download = item.json().get("@microsoft.graph.downloadUrl");
            URI address = download == null ? null : URI.create(String.valueOf(download));
            if (address == null || !addresses.downloadAllowed(address)) {
                failWith("fileStore.readFailed", "Graph handed out no download address on SharePoint for " + path,
                        Map.of("path", path));
            }
            return download(address, path, offset, maxBytes);
        }

        /**
         * The simple upload, which replaces the item whole (Graph's default for an existing name). A file someone has
         * open in Office answers 423: fileStore.fileLocked, so the run fails and the next one tries again.
         */
        @Override
        public void write(String path, byte[] content) {
            URI address = graph(itemPath(path) + ":/content");
            Answer answer = upload(address, content, token);
            if (answer.status() == 401) {
                token = token(Session.this, true);
                answer = upload(address, content, token);
            }
            if (answer.status() == 423) {
                failWith("fileStore.fileLocked", "The file " + path + " is open somewhere and locked - the next run "
                        + "tries again", Map.of("path", path));
            }
            if (answer.status() >= 300) {
                failWith("fileStore.writeFailed", "Writing " + path + " failed: Graph answered " + answer.status()
                        + (answer.status() == 403 ? " - the app may not write to the site" : "")
                        + (answer.status() == 404 ? " - its folder does not exist" : ""), Map.of("path", path));
            }
        }

        @Override
        public void close() {
            // Nothing held: every request is a connection of its own, the token expires by itself
        }

        /** A GET with the token - a token expired during a long session is fetched once more. */
        private Answer get(URI address) {
            Answer answer = call("GET", address, token);
            if (answer.status() == 401) {
                token = token(this, true);
                answer = call("GET", address, token);
            }
            return answer;
        }

        /** The item's address below the library's root: "/" the root, "/a b/c" root:/a%20b/c. */
        private String itemPath(String path) {
            if (path.equals("/") || path.isEmpty()) {
                return "/drives/" + drive + "/root";
            }
            StringBuilder encoded = new StringBuilder();
            for (String segment : path.split("/")) {
                if (!segment.isEmpty()) {
                    encoded.append('/').append(encode(segment));
                }
            }
            return "/drives/" + drive + "/root:" + encoded;
        }

        /** The next page of a listing - only from Graph itself, the token goes with it. */
        private @Nullable URI nextPage(Answer answer) {
            Object next = answer.json().get("@odata.nextLink");
            if (next == null) {
                return null;
            }
            URI address = URI.create(String.valueOf(next));
            if (!addresses.isGraph(address)) {
                failWith("fileStore.readFailed", "Graph named a next page outside itself", Map.of("path", "/"));
            }
            return address;
        }
    }

    /** A file or a folder; null for anything else (a OneNote notebook, another package). */
    private static @Nullable RemoteFile entry(Map<?, ?> item) {
        String name = String.valueOf(item.get("name"));
        if (item.get("folder") != null) {
            return new RemoteFile(name, 0, null, true);
        }
        if (item.get("file") == null) {
            return null;
        }
        long size = item.get("size") instanceof Number n ? n.longValue() : 0;
        Object modified = item.get("lastModifiedDateTime");
        return new RemoteFile(name, size, modified == null ? null : Instant.parse(String.valueOf(modified)), false);
    }

    private byte[] download(URI address, String path, long offset, int maxBytes) {
        try {
            HttpURLConnection connection = connect("GET", address);
            connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + maxBytes - 1));
            int status = connection.getResponseCode();
            if (status == 416) {
                return new byte[0];
            }
            if (status != 200 && status != 206) {
                failWith("fileStore.readFailed", "Reading " + path + " failed: " + status, Map.of("path", path));
            }
            try (InputStream in = connection.getInputStream()) {
                if (status == 200 && !skip(in, offset)) {
                    return new byte[0];
                }
                return in.readNBytes(maxBytes);
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            throw StoreFailures.readFailed(path, e);
        }
    }

    /** Reads past the first bytes of a whole file; false when it ends before. */
    private static boolean skip(InputStream in, long bytes) throws IOException {
        long left = bytes;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    return false;
                }
                skipped = 1;
            }
            left -= skipped;
        }
        return true;
    }

    /**
     * One request; Graph throttling (429, 503) is waited for once if it asks for at most {@link #MAX_RETRY_AFTER} - it
     * throttles as part of its normal behaviour under load, not as a failure.
     */
    private Answer call(String method, URI address, @Nullable String token) {
        Answer answer = send(method, address, token);
        if (answer.status() != 429 && answer.status() != 503) {
            return answer;
        }
        Duration wait = answer.retryAfter();
        if (wait.compareTo(MAX_RETRY_AFTER) > 0) {
            throw throttled();
        }
        sleep(wait);
        answer = send(method, address, token);
        if (answer.status() == 429 || answer.status() == 503) {
            throw throttled();
        }
        return answer;
    }

    /** A PUT of the content, retried once after Graph asked to wait - as a GET is. */
    private Answer upload(URI address, byte[] content, String token) {
        Answer answer = sendBody(address, content, token);
        if (answer.status() != 429 && answer.status() != 503) {
            return answer;
        }
        Duration wait = answer.retryAfter();
        if (wait.compareTo(MAX_RETRY_AFTER) > 0) {
            throw throttled();
        }
        sleep(wait);
        return sendBody(address, content, token);
    }

    private Answer sendBody(URI address, byte[] content, String token) {
        try {
            HttpURLConnection connection = connect("PUT", address);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(content.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(content);
            }
            int status = connection.getResponseCode();
            try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                byte[] body = in == null ? new byte[0] : in.readNBytes(MAX_JSON_BYTES);
                return new Answer(status, body, connection.getHeaderField("Retry-After"));
            } finally {
                connection.disconnect();
            }
        } catch (SSLException e) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.tlsFailed", "The TLS connection to "
                    + address.getHost() + " failed: " + e.getMessage(), Map.of("host", address.getHost()));
        } catch (IOException e) {
            throw unreachable(address, e);
        }
    }

    private Answer send(String method, URI address, @Nullable String token) {
        try {
            HttpURLConnection connection = connect(method, address);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int status = connection.getResponseCode();
            try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                byte[] body = in == null ? new byte[0] : in.readNBytes(MAX_JSON_BYTES);
                return new Answer(status, body, connection.getHeaderField("Retry-After"));
            } finally {
                connection.disconnect();
            }
        } catch (SSLException e) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.tlsFailed", "The TLS connection to "
                    + address.getHost() + " failed: " + e.getMessage(), Map.of("host", address.getHost()));
        } catch (IOException e) {
            throw unreachable(address, e);
        }
    }

    private static ServiceException unreachable(URI address, Throwable e) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.unreachable", address.getHost()
                + " could not be reached: " + e, Map.of("host", address.getHost()));
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private HttpURLConnection connect(String method, URI address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) address.toURL().openConnection();
        if (connection instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(addresses.sslSocketFactory());
        }
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        // A redirect would take the token along to wherever it points
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        return connection;
    }

    /** An answer of Graph or the login: its status, body and the wait a throttling one asks for. */
    private final class Answer {

        private final int status;
        private final byte[] body;
        private final @Nullable String retryAfter;
        private Map<?, ?> parsed;

        private Answer(int status, byte[] body, @Nullable String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }

        int status() {
            return status;
        }

        /** The body as JSON - empty for none or one that is not JSON (a proxy's error page). */
        Map<?, ?> json() {
            if (parsed == null) {
                try {
                    parsed = body.length == 0 ? Map.of() : json.readValue(body, Map.class);
                } catch (IOException e) {
                    parsed = Map.of();
                }
            }
            return parsed;
        }

        Duration retryAfter() {
            try {
                return retryAfter == null ? DEFAULT_RETRY_AFTER : Duration.ofSeconds(Long.parseLong(retryAfter.strip()));
            } catch (NumberFormatException e) {
                return DEFAULT_RETRY_AFTER;
            }
        }
    }

    /** An answer that is not 200: refused rights (401, 403) are accessDenied, anything else a failed read. */
    private Answer requireOk(Answer answer, GraphSettings settings) {
        requireAllowed(answer, settings);
        if (answer.status() != 200) {
            failWith("fileStore.readFailed", "Graph answered " + answer.status() + ": " + graphError(answer),
                    Map.of("path", "/"));
        }
        return answer;
    }

    private void requireAllowed(Answer answer, GraphSettings settings) {
        if (answer.status() == 401 || answer.status() == 403) {
            failWith("fileStore.accessDenied", "The app may not read " + settings.siteUrl() + " - grant it the site "
                    + "(Sites.Selected)", Map.of("site", settings.siteUrl()));
        }
    }

    /**
     * A page's entries - a page without its list (a proxy's page answered with 200, a body cut off) is a failure, not an
     * empty page: a knowledge source would take the pages before it for the whole folder.
     */
    private static List<Map<?, ?>> entries(Answer answer, String folder) {
        if (!(answer.json().get("value") instanceof List<?> values)) {
            throw StoreFailures.listingBroken(folder);
        }
        List<Map<?, ?>> entries = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> entry) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static String graphError(Answer answer) {
        return answer.json().get("error") instanceof Map<?, ?> error ? String.valueOf(error.get("code")) : "";
    }

    private URI graph(String path) {
        return URI.create(addresses.graph() + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static <T> T failWith(String code, String message, Map<String, String> params) {
        throw new ServiceException(HttpStatus.BAD_GATEWAY, code, message, params);
    }

    private static ServiceException throttled() {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.throttled", "Microsoft Graph is throttling "
                + "the requests - try again later");
    }

    private static void sleep(Duration wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw throttled();
        }
    }
}
