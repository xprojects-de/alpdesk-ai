package de.alpdesk.services.sandbox;

import de.alpdesk.entities.tenant.Tenant;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sandbox instances the chat's code runs in (docs/architecture/code-execution.md): containers of their own next to
 * the app, reached over HTTP. Every instance belongs to one tenant - the admin enters its addresses and token at the
 * tenant -, so the container keeps tenants apart. An instance runs one session at a time - that keeps the users of a
 * tenant apart -, so an answer that runs code leases a free instance of its tenant for its duration. The lease is kept
 * here in memory, by address: the app runs as one instance.
 * <p>
 * No instance answering or all busy: the tools are not offered or tell the model so - the answer goes on without them.
 */
@Singleton
public class Sandboxes {

    private static final Logger LOG = LoggerFactory.getLogger(Sandboxes.class);
    /** How long an instance's health is trusted before it is asked again. */
    private static final Duration HEALTH_TTL = Duration.ofSeconds(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration WAIT_STEP = Duration.ofSeconds(1);

    private final Duration timeout;
    private final Duration wait;
    private final JsonMapper json;
    private final HttpClient client;
    private final Set<String> leased = new HashSet<>();
    private final Map<String, Health> health = new ConcurrentHashMap<>();

    /**
     * What an instance's /health said: whether it answered, whether a session runs there, whether it is the video
     * image (Backend/podman/sandbox/Dockerfile target "video": ffmpeg and RTMPose), the README of an image an operator
     * built on ours (its own programs, null without one) - and why it takes no session until it is started again (it
     * may not send signals, a session could not be ended cleanly), null while it does.
     */
    private record Health(boolean up, boolean busy, boolean video, @Nullable String extras, @Nullable String unusable,
                          Instant checked) {
        static Health down() {
            return new Health(false, false, false, null, null, Instant.now());
        }

        boolean usable() {
            return up && unusable == null;
        }
    }

    /** The instance answered 404: it ended the session itself (idle or too long) - its files and variables are gone. */
    public static final class SessionGone extends Exception {
    }

    /** The instance answered 401: the tenant's token is not the one it was started with - no instance of it will do. */
    public static final class TokenRefused extends Exception {
    }

    /**
     * The instance refused one of the session's files (413 too large, 400 not writable) - another instance or a second
     * try would refuse it the same way.
     */
    public static final class FilesRefused extends Exception {
    }

    /**
     * What the admin's check of an instance found - UNUSABLE: it answers but takes no session until it is started again
     * (its log says why: it may not send signals, a session could not be ended cleanly).
     */
    public enum Status { REACHABLE, BUSY, TOKEN_REFUSED, UNREACHABLE, UNUSABLE }

    /** Whether a tenant's instances examine videos - unknown while none of them answers. */
    public enum Video { YES, NO, UNKNOWN }

    public Sandboxes(@Value("${app.sandbox.timeout:60s}") Duration timeout, @Value("${app.sandbox.wait:15s}") Duration wait,
                     JsonMapper json) {
        this.timeout = timeout;
        this.wait = wait;
        this.json = json;
        // HTTP/1.1: on plain HTTP Java's client otherwise asks every request for an upgrade to h2c, which the
        // sandbox's server refuses and logs as a warning each time.
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** A tenant's stored addresses (comma separated) as a list, without trailing slashes; empty for none. */
    public static List<String> addresses(@Nullable String urls) {
        if (urls == null) {
            return List.of();
        }
        List<String> addresses = new ArrayList<>();
        for (String url : urls.split(",")) {
            String address = url.strip().replaceAll("/+$", "");
            if (!address.isEmpty()) {
                addresses.add(address);
            }
        }
        return addresses;
    }

    /** Whether code can run on these instances at all: at least one answers and takes sessions - busy or not. */
    public boolean available(List<String> urls) {
        for (String url : urls) {
            if (healthy(url) && health.get(url).usable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether these instances (a tenant's) examine videos, as their /health last said within its time to live: yes when
     * every one answering is the video image - a session lands on any free one -, no when one answering is not.
     */
    public Video video(List<String> urls) {
        boolean answered = false;
        for (String url : urls) {
            if (!healthy(url)) {
                continue;
            }
            answered = true;
            if (!health.get(url).video()) {
                return Video.NO;
            }
        }
        return answered ? Video.YES : Video.UNKNOWN;
    }

    /** Whether the tenant's instances examine videos - UNKNOWN for a tenant without any. */
    public Video video(Tenant tenant) {
        return video(addresses(tenant.sandboxUrls()));
    }

    /** Whether one instance said it is the video image - null when it did not answer (for the admin's check). */
    public @Nullable Boolean video(String url) {
        Health known = health.get(url);
        return known != null && known.up() ? known.video() : null;
    }

    /**
     * The README of an image built on ours that these instances (a tenant's) carry, as their /health last said: only
     * when every one answering reports the same text - a session lands on any free one, so the model must not be told
     * of programs one of them lacks. Null when they differ, none has one or none answers.
     */
    public @Nullable String extras(List<String> urls) {
        String agreed = null;
        for (String url : urls) {
            if (!healthy(url)) {
                continue;
            }
            String extras = health.get(url).extras();
            if (extras == null || (agreed != null && !agreed.equals(extras))) {
                return null;
            }
            agreed = extras;
        }
        return agreed;
    }

    /** The README of the tenant's own sandbox image - null for a tenant without one, or whose instances differ. */
    public @Nullable String extras(Tenant tenant) {
        return extras(addresses(tenant.sandboxUrls()));
    }

    /** Whether one instance carries a README of its own programs - null when it did not answer (for the admin's check). */
    public @Nullable Boolean extras(String url) {
        Health known = health.get(url);
        return known != null && known.up() ? known.extras() != null : null;
    }

    /**
     * Whether the instances answering report the same README, or all none (for the admin's check, after it asked each
     * one): a session lands on any of them, so the model gets the text only when they agree.
     */
    public boolean extrasAgree(List<String> urls) {
        Set<String> seen = new HashSet<>();
        boolean answered = false;
        for (String url : urls) {
            Health known = health.get(url);
            if (known == null || !known.up()) {
                continue;
            }
            seen.add(known.extras() == null ? "" : known.extras());
            answered = true;
        }
        return !answered || seen.size() == 1;
    }

    /**
     * A session on a free one of these instances (a tenant's) with these files in its data/ - waiting up to
     * app.sandbox.wait for one; empty when none became free or none answers, TokenRefused when an instance refused the
     * token, FilesRefused when it refused a file. Called from a tool: it runs on the answer's thread, which may wait. An
     * instance is asked for its health first; the session is opened empty and the files are streamed into it one by
     * one - a video does not fit a JSON body, and the instance says it is busy before it reads one.
     */
    public Optional<SandboxSession> open(List<String> urls, String token, List<SandboxFile> files)
            throws TokenRefused, FilesRefused {
        if (!available(urls)) {
            return Optional.empty();
        }
        Instant until = Instant.now().plus(wait);
        try {
            do {
                for (String url : freeInstances(urls)) {
                    Optional<SandboxSession> session = tryOpen(url, token, files);
                    if (session.isPresent()) {
                        return session;
                    }
                }
                if (!sleep()) {
                    break;
                }
            } while (Instant.now().isBefore(until));
        } catch (TokenRefused e) {
            LOG.warn("A sandbox of {} refused the tenant's token - check it at the tenant", urls);
            throw e;
        }
        return Optional.empty();
    }

    /**
     * The admin's check of one instance with its tenant's token: it answers, the token is taken (a session opened
     * without files and ended at once), it is busy - an answer of this app holds it, or another session runs there -,
     * or it takes no session until it is started again. Waits for nothing.
     */
    public Status check(String url, String token) {
        Health current = askHealth(url);
        if (!current.up()) {
            return Status.UNREACHABLE;
        }
        if (!current.usable()) {
            return Status.UNUSABLE;
        }
        if (!lease(url)) {
            return Status.BUSY;
        }
        try {
            HttpResponse<String> response = send(url, token, "POST", "/sessions", Map.of("files", List.of()));
            return switch (response.statusCode()) {
                case 201 -> {
                    send(url, token, "DELETE", "/sessions/" + sessionId(response), null);
                    yield Status.REACHABLE;
                }
                case 401 -> Status.TOKEN_REFUSED;
                case 409 -> Status.BUSY;
                default -> {
                    LOG.warn("Sandbox {} answered the check with HTTP {}", url, response.statusCode());
                    yield Status.UNREACHABLE;
                }
            };
        } catch (IOException e) {
            return Status.UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Status.UNREACHABLE;
        } finally {
            release(url);
        }
    }

    private Optional<SandboxSession> tryOpen(String url, String token, List<SandboxFile> files)
            throws TokenRefused, FilesRefused {
        if (!lease(url)) {
            return Optional.empty();
        }
        boolean opened = false;
        try {
            HttpResponse<String> response = send(url, token, "POST", "/sessions", Map.of("files", List.of()));
            if (response.statusCode() == 201) {
                String sessionId = sessionId(response);
                putFiles(url, token, sessionId, files);
                opened = true;
                return Optional.of(new SandboxSession(this, url, token, sessionId));
            }
            if (response.statusCode() == 401) {
                throw new TokenRefused();
            }
            if (response.statusCode() != 409) {
                LOG.warn("Sandbox {} refused a session's files: HTTP {}", url, response.statusCode());
                throw new FilesRefused();
            }
        } catch (IOException e) {
            unreachable(url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (!opened) {
                release(url);
            }
        }
        return Optional.empty();
    }

    /** The files into a session just opened - ended again when one of them is refused or the instance fails. */
    private void putFiles(String url, String token, String sessionId, List<SandboxFile> files)
            throws IOException, InterruptedException, TokenRefused, FilesRefused {
        boolean complete = false;
        try {
            for (SandboxFile file : files) {
                putFile(url, token, sessionId, file);
            }
            complete = true;
        } finally {
            if (!complete) {
                endSession(url, token, sessionId);
            }
        }
    }

    private void putFile(String url, String token, String sessionId, SandboxFile file)
            throws IOException, InterruptedException, TokenRefused, FilesRefused {
        HttpRequest.BodyPublisher body = file.size() == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> open(file)),
                file.size());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/sessions/" + sessionId + "/files?name="
                        + URLEncoder.encode(file.name(), StandardCharsets.UTF_8)))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/octet-stream")
                .PUT(body)
                .build();
        int status;
        try {
            status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException | ContentUnreadable e) {
            if (e instanceof ContentUnreadable || e.getCause() instanceof ContentUnreadable) {
                // The app's storage failed, not the instance: it is not marked unreachable.
                LOG.warn("A session's file could not be read from the storage: {}", e.getMessage());
                throw new FilesRefused();
            }
            throw e;
        }
        if (status == 201) {
            return;
        }
        if (status == 401) {
            throw new TokenRefused();
        }
        LOG.warn("Sandbox {} refused a session's file: HTTP {}", url, status);
        throw new FilesRefused();
    }

    private static InputStream open(SandboxFile file) {
        try {
            return file.content().open();
        } catch (IOException e) {
            throw new ContentUnreadable(e);
        }
    }

    /** A file's content could not be read on this side - the body publisher only passes unchecked exceptions on. */
    private static final class ContentUnreadable extends UncheckedIOException {
        ContentUnreadable(IOException cause) {
            super(cause);
        }
    }

    /**
     * A file the session's code wrote, read up to maxBytes - a larger one is refused by its declared length before
     * it is read. SessionGone when the instance no longer has the session.
     */
    SandboxSession.FileResult fetch(String url, String token, String sessionId, String path, long maxBytes)
            throws IOException, InterruptedException, SessionGone {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/sessions/" + sessionId + "/files?path="
                        + URLEncoder.encode(path, StandardCharsets.UTF_8)))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                throw new SessionGone();
            }
            if (response.statusCode() == 400) {
                return SandboxSession.FileResult.error("there is no file \"" + path + "\" in the session - give its path "
                        + "relative to the directory the code runs in, e.g. out/frame_001.jpg");
            }
            if (response.statusCode() != 200) {
                throw new IOException("the sandbox answered HTTP " + response.statusCode());
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (length > maxBytes) {
                return SandboxSession.FileResult.error("\"" + path + "\" has " + length / (1024 * 1024)
                        + " MB - at most " + maxBytes / (1024 * 1024) + " MB");
            }
            byte[] content = body.readNBytes((int) Math.min(Integer.MAX_VALUE, maxBytes + 1));
            if (content.length > maxBytes) {
                return SandboxSession.FileResult.error("\"" + path + "\" is larger than " + maxBytes / (1024 * 1024) + " MB");
            }
            return new SandboxSession.FileResult(content, null);
        }
    }

    private String sessionId(HttpResponse<String> created) throws IOException {
        return String.valueOf(json.readValue(created.body(), Map.class).get("session"));
    }

    /**
     * One call of a session's code; the answer's fields as the instance sent them. SessionGone when the instance no
     * longer has the session - it ended it itself -, IOException when it does not answer or answers with an error.
     */
    Map<String, Object> call(String url, String token, String path, Map<String, Object> body)
            throws IOException, InterruptedException, SessionGone {
        HttpResponse<String> response = send(url, token, "POST", path, body);
        if (response.statusCode() == 404) {
            throw new SessionGone();
        }
        if (response.statusCode() != 200) {
            throw new IOException("the sandbox answered HTTP " + response.statusCode());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = json.readValue(response.body(), LinkedHashMap.class);
        return answer;
    }

    /** Ends a session: the instance kills what it started and deletes its files; the lease ends either way. */
    void close(String url, String token, String sessionId) {
        try {
            endSession(url, token, sessionId);
        } finally {
            release(url);
        }
    }

    private void endSession(String url, String token, String sessionId) {
        try {
            send(url, token, "DELETE", "/sessions/" + sessionId, null);
        } catch (IOException e) {
            // The instance ends an idle session by itself.
            LOG.warn("Sandbox {} could not be told to end a session: {}", url, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void unreachable(String url, IOException e) {
        health.put(url, Health.down());
        LOG.warn("Sandbox {} is not reachable: {}", url, e.getMessage());
    }

    private HttpResponse<String> send(String url, String token, String method, String path, Map<String, Object> body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Whether the instance answers - as last asked within HEALTH_TTL. */
    private boolean healthy(String url) {
        Health known = health.get(url);
        if (known != null && known.checked().plus(HEALTH_TTL).isAfter(Instant.now())) {
            return known.up();
        }
        return askHealth(url).up();
    }

    /** Asks the instance's /health now and notes the answer. */
    private Health askHealth(String url) {
        Health known = health.get(url);
        Health current = fetchHealth(url);
        if (known != null && known.up() != current.up()) {
            LOG.warn("Sandbox {} is {}", url, current.up() ? "reachable again" : "not reachable");
        }
        if (current.unusable() != null && (known == null || known.unusable() == null)) {
            LOG.warn("Sandbox {} takes no session until it is started again: {}", url, current.unusable());
        }
        health.put(url, current);
        return current;
    }

    private Health fetchHealth(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/health")).timeout(CONNECT_TIMEOUT).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Health.down();
            }
            Map<?, ?> answer = json.readValue(response.body(), Map.class);
            return new Health(true, Boolean.TRUE.equals(answer.get("busy")), Boolean.TRUE.equals(answer.get("video")),
                    answer.get("extras") instanceof String extras && !extras.isBlank() ? extras : null,
                    answer.get("unusable") instanceof String reason ? reason : null, Instant.now());
        } catch (IOException e) {
            return Health.down();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down();
        }
    }

    /** The instances no answer of this app holds and whose /health - asked now - says no session runs. */
    private List<String> freeInstances(List<String> urls) {
        List<String> free = new ArrayList<>();
        for (String url : urls) {
            if (isLeased(url)) {
                continue;
            }
            Health current = askHealth(url);
            if (current.up() && !current.busy()) {
                free.add(url);
            }
        }
        return free;
    }

    private synchronized boolean isLeased(String url) {
        return leased.contains(url);
    }

    private synchronized boolean lease(String url) {
        return leased.add(url);
    }

    private synchronized void release(String url) {
        leased.remove(url);
    }

    private boolean sleep() {
        try {
            Thread.sleep(WAIT_STEP);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
