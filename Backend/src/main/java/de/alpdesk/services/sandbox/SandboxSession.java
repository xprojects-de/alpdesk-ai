package de.alpdesk.services.sandbox;

import java.io.IOException;
import java.util.Map;

/**
 * One answer's session on a sandbox instance: its Python (one namespace for the whole answer, like a notebook) and its
 * shell, working on the files it was opened with. A failure of the instance is a result for the model, not an error of
 * the answer. Closed with the answer - the instance then deletes everything of it. It holds its tenant's token for its
 * calls.
 */
public class SandboxSession {

    /** What the model reads when the instance ended the session itself. */
    static final String GONE = "the session ended - it was idle or running too long; its variables and the files it "
            + "wrote are gone. The next call starts a new session with the question's files";
    private static final String UNREACHABLE = "the code execution failed - it is not reachable right now";

    private final Sandboxes sandboxes;
    private final String url;
    private final String token;
    private final String id;
    private boolean closed;
    private volatile boolean gone;

    SandboxSession(Sandboxes sandboxes, String url, String token, String id) {
        this.sandboxes = sandboxes;
        this.url = url;
        this.token = token;
        this.id = id;
    }

    /** What Python printed, or its error (a traceback, a timeout). */
    public record PythonResult(String output, String error) {
    }

    /** What the command printed (and wrote to stderr) and its exit code; null when it could not run at all. */
    public record ShellResult(String output, Integer exitCode) {
    }

    /** A file the code wrote, or why it could not be read - for the model. */
    public record FileResult(byte[] content, String error) {
        static FileResult error(String error) {
            return new FileResult(null, error);
        }
    }

    public PythonResult python(String code) {
        Map<String, Object> answer = call("python", Map.of("code", code));
        return new PythonResult(text(answer.get("output")), answer.get("error") == null ? null : text(answer.get("error")));
    }

    public ShellResult shell(String command) {
        Map<String, Object> answer = call("shell", Map.of("command", command));
        if (answer.get("error") != null) {
            return new ShellResult(text(answer.get("error")), null);
        }
        return new ShellResult(text(answer.get("output")),
                answer.get("exitCode") instanceof Number exitCode ? exitCode.intValue() : null);
    }

    /** A file the session's code wrote (a frame, a clip), at most maxBytes. */
    public FileResult file(String path, long maxBytes) {
        try {
            return sandboxes.fetch(url, token, id, path, maxBytes);
        } catch (Sandboxes.SessionGone e) {
            endedByInstance();
            return FileResult.error(GONE);
        } catch (IOException e) {
            sandboxes.unreachable(url, e);
            return FileResult.error(UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FileResult.error("interrupted");
        }
    }

    /**
     * Whether the instance ended this session itself - idle for too long (a model thinking for minutes between two
     * calls) or running too long: the answer's next call opens a new one instead.
     */
    public boolean isGone() {
        return gone;
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        sandboxes.close(url, token, id);
    }

    private Map<String, Object> call(String kind, Map<String, Object> body) {
        try {
            return sandboxes.call(url, token, "/sessions/" + id + "/" + kind, body);
        } catch (Sandboxes.SessionGone e) {
            endedByInstance();
            return Map.of("output", "", "error", GONE);
        } catch (IOException e) {
            sandboxes.unreachable(url, e);
            return Map.of("output", "", "error", UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("output", "", "error", "interrupted");
        }
    }

    /** The instance has ended the session: the lease on it ends too, so the next call may open a new one there. */
    void endedByInstance() {
        gone = true;
        close();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
