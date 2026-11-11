package de.alpdesk.services.sandbox

import io.micronaut.context.annotation.Replaces
import io.micronaut.json.JsonMapper
import jakarta.inject.Singleton

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for the sandbox instances in the specs (docs/architecture/code-execution.md): no container is reached.
 * Python answers "python: <code>" plus the files it got, the shell "shell: <command>"; "fail" in the code answers with
 * an error, "long" with more output than the tools pass on, "gone" as an instance that ended the session by itself.
 * Every session is recorded with the instances and token it
 * was opened with, its files, calls and whether it was closed. The files its code "wrote" are the spec's (written),
 * read back with file(). The README of an image built on ours: extras for every instance, extrasOf for one. The
 * admin's check answers from statuses (reachable while
 * up, else unreachable) and records the token. Unavailable unless a spec says otherwise; reset() in setup().
 */
@Singleton
@Replaces(Sandboxes)
class FakeSandboxes extends Sandboxes {

    /** Whether an instance answers. */
    volatile boolean up = false
    /** Every instance is busy: no session opens. */
    volatile boolean busy = false
    /** The instances are the video image (ffmpeg, RTMPose) - what their /health says while they answer. */
    volatile boolean video = true
    /** The README the instances' /health reports (an image built on ours) - null: ours, without one. */
    volatile String extras = null
    /** The README of one instance, by address (null: none) - unlisted: extras. */
    final Map<String, String> extrasOf = Collections.synchronizedMap(new HashMap<String, String>())
    /** The instances refuse the token. */
    volatile boolean tokenRefused = false
    /** The instances refuse the session's files (too large together). */
    volatile boolean filesRefused = false
    final List<FakeSession> sessions = new CopyOnWriteArrayList<>()
    /** What the admin's check answers per address - unlisted: reachable while up. */
    final Map<String, Sandboxes.Status> statuses = new ConcurrentHashMap<>()
    /** The tokens the admin's checks were made with. */
    final List<String> checkedTokens = new CopyOnWriteArrayList<>()
    /** The files the sessions' code wrote, by path - what view_images and hand_out_file read. */
    final Map<String, byte[]> written = new ConcurrentHashMap<>()

    FakeSandboxes(JsonMapper json) {
        super(Duration.ofSeconds(1), Duration.ZERO, json)
    }

    void reset() {
        up = false
        busy = false
        video = true
        extras = null
        extrasOf.clear()
        tokenRefused = false
        filesRefused = false
        sessions.clear()
        statuses.clear()
        checkedTokens.clear()
        written.clear()
    }

    @Override
    boolean available(List<String> urls) {
        up && !urls.isEmpty()
    }

    @Override
    Sandboxes.Video video(List<String> urls) {
        !up || urls.isEmpty() ? Sandboxes.Video.UNKNOWN : video ? Sandboxes.Video.YES : Sandboxes.Video.NO
    }

    @Override
    Boolean video(String url) {
        up ? video : null
    }

    @Override
    String extras(List<String> urls) {
        def texts = urls.collect { extrasAt(it) }.unique()
        up && texts.size() == 1 ? texts.first() : null
    }

    @Override
    Boolean extras(String url) {
        up ? extrasAt(url) != null : null
    }

    @Override
    boolean extrasAgree(List<String> urls) {
        !up || urls.collect { extrasAt(it) }.unique().size() <= 1
    }

    private String extrasAt(String url) {
        extrasOf.containsKey(url) ? extrasOf[url] : extras
    }

    @Override
    Optional<SandboxSession> open(List<String> urls, String token, List<SandboxFile> files) {
        if (!up || busy || urls.isEmpty()) {
            return Optional.empty()
        }
        if (tokenRefused) {
            throw new Sandboxes.TokenRefused()
        }
        if (filesRefused) {
            throw new Sandboxes.FilesRefused()
        }
        def session = new FakeSession(this, urls, token, files, written)
        sessions << session
        Optional.of(session)
    }

    @Override
    Sandboxes.Status check(String url, String token) {
        checkedTokens << token
        statuses[url] ?: (up ? Sandboxes.Status.REACHABLE : Sandboxes.Status.UNREACHABLE)
    }

    static class FakeSession extends SandboxSession {

        final List<String> urls
        final String token
        final Map<String, String> files
        final Map<String, byte[]> written
        final List<String> calls = new CopyOnWriteArrayList<>()
        volatile boolean closed = false

        FakeSession(Sandboxes sandboxes, List<String> urls, String token, List<SandboxFile> files, Map<String, byte[]> written) {
            super(sandboxes, urls.first(), token, UUID.randomUUID().toString().replace("-", ""))
            this.urls = List.copyOf(urls)
            this.token = token
            this.files = files.collectEntries { [(it.name()): new String(it.content().open().bytes, StandardCharsets.UTF_8)] }
            this.written = written
        }

        @Override
        FileResult file(String path, long maxBytes) {
            calls << "file: " + path
            byte[] content = written[path]
            if (content == null) {
                return new FileResult(null, "there is no file \"" + path + "\" in the session")
            }
            content.length > maxBytes ? new FileResult(null, "at most " + maxBytes + " bytes") : new FileResult(content, null)
        }

        @Override
        PythonResult python(String code) {
            calls << "python: " + code
            if (code.contains("fail")) {
                return new PythonResult("", "Traceback: ZeroDivisionError")
            }
            if (code.contains("long")) {
                return new PythonResult("x" * 30_000, null)
            }
            if (code.contains("gone")) {
                endedByInstance()
                return new PythonResult("", GONE)
            }
            new PythonResult("python: " + code + " [files: " + files.keySet().sort().join(", ") + "]\n", null)
        }

        @Override
        ShellResult shell(String command) {
            calls << "shell: " + command
            new ShellResult("shell: " + command + "\n", 0)
        }

        @Override
        synchronized void close() {
            closed = true
        }
    }
}
