package de.alpdesk.services.tools;

import de.alpdesk.entities.chat.ConversationFile;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.security.SecretCipher;
import de.alpdesk.services.chat.ChatFileService;
import de.alpdesk.services.chat.SentFile;
import de.alpdesk.services.sandbox.SandboxFile;
import de.alpdesk.services.sandbox.SandboxSession;
import de.alpdesk.services.sandbox.Sandboxes;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What run_python and run_shell share (docs/architecture/code-execution.md): whether code may run for this question -
 * the admin entered the tenant's own sandbox and one of its instances answers -, and the answer's one session on the
 * tenant's instances, opened at the first call with the question's files in data/: every file sent with it, small or
 * large, and the conversation's files.
 */
@Singleton
class CodeSessions {

    /** What a call's output may take of the model's context. */
    static final int MAX_OUTPUT = 20_000;
    static final String BUSY = "the code execution is busy right now - go on without it";
    static final String TOKEN_REFUSED = "the code execution refused the tenant's token - an admin checks the sandbox at "
            + "the tenant; go on without it";
    static final String FILES_REFUSED = "the code execution refused a file of this question - too large, or a "
            + "name it cannot write; go on without it";

    /** Why there is no session for this call - the text the model reads. */
    static final class Unavailable extends Exception {
        Unavailable(String reason) {
            super(reason);
        }
    }

    private final Sandboxes sandboxes;
    private final ChatFileService files;
    private final SecretCipher cipher;

    CodeSessions(Sandboxes sandboxes, ChatFileService files, SecretCipher cipher) {
        this.sandboxes = sandboxes;
        this.files = files;
        this.cipher = cipher;
    }

    /** Whether the tenant's sandbox is the video image (ffmpeg, RTMPose) - as its instances' /health last said. */
    boolean examinesVideo(ToolContext context) {
        return sandboxes.video(context.tenant()) == Sandboxes.Video.YES;
    }

    boolean available(ToolContext context) {
        Tenant tenant = context.tenant();
        return tenant.sandboxToken() != null && sandboxes.available(Sandboxes.addresses(tenant.sandboxUrls()));
    }

    /**
     * The answer's session - opened now on one of the tenant's instances if this is its first call, or if the instance
     * ended the one before by itself. Unavailable, with the reason for the model, when none became free or the token
     * is refused. The token is decrypted for this answer only.
     */
    SandboxSession session(ToolContext context) throws Unavailable {
        SandboxSession current = context.sandbox();
        if (current != null && !current.isGone()) {
            return current;
        }
        Tenant tenant = context.tenant();
        Optional<SandboxSession> opened;
        try {
            opened = sandboxes.open(Sandboxes.addresses(tenant.sandboxUrls()), cipher.decrypt(tenant.sandboxToken()),
                    sessionFiles(context));
        } catch (Sandboxes.TokenRefused e) {
            throw new Unavailable(TOKEN_REFUSED);
        } catch (Sandboxes.FilesRefused e) {
            throw new Unavailable(FILES_REFUSED);
        }
        SandboxSession session = opened.orElseThrow(() -> new Unavailable(BUSY));
        context.sandbox(session);
        return session;
    }

    static String cut(String text) {
        return text.length() <= MAX_OUTPUT ? text
                : text.substring(0, MAX_OUTPUT) + "\n[... " + (text.length() - MAX_OUTPUT) + " more characters cut - "
                + "print less, e.g. counts or selected lines]";
    }

    /**
     * The files for the session's data/, read only now - most answers never open a session. The conversation's come
     * streamed from the storage: a video is not held in memory.
     */
    private List<SandboxFile> sessionFiles(ToolContext context) {
        List<SandboxFile> sessionFiles = new ArrayList<>();
        for (SentFile file : context.sentFiles()) {
            sessionFiles.add(SandboxFile.of(file.name().strip(), file.content().getBytes(StandardCharsets.UTF_8)));
        }
        if (context.conversationHasFiles()) {
            for (ConversationFile file : files.findByConversation(context.dataSource(), context.conversationId())) {
                if (file.isExpired()) {
                    continue;
                }
                sessionFiles.add(new SandboxFile(file.fileName(), file.sizeBytes(), () -> files.openContent(file)));
            }
        }
        return sessionFiles;
    }
}
