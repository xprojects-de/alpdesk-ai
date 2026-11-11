package de.alpdesk.services.chat;

import de.alpdesk.entities.chat.ConversationFile;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.chat.ConversationFileRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.Retention;
import de.alpdesk.services.tenant.TenantDatabases;
import de.alpdesk.storage.FileStorage;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Deletes the chat's files from the storage once their retention time is over (docs/roadmap.md point 35): an uploaded
 * video app.chat.video-retention-hours after its upload - it has up to app.chat.video-max-size -, every other file
 * (uploaded, created by the model, handed out of the sandbox, returned by a connected software) app.chat.file-retention-days
 * after it was stored. Left empty, a retention is off and those files are kept. The file's row stays, marked expired
 * and without its text (extracted on upload, a transcript) - the conversation lists it, the model is told it is gone.
 * <p>
 * Built at start ({@code @Context}), not when the scheduler first runs it: a wrong retention must stop the start, and
 * the log says at once what will be deleted.
 */
@Context
public class ChatFileCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(ChatFileCleanupJob.class);

    private final TenantRepository tenants;
    private final TenantDatabases databases;
    private final ConversationFileRepository files;
    private final FileStorage storage;
    private final Optional<Duration> videoRetention;
    private final Optional<Duration> fileRetention;

    public ChatFileCleanupJob(TenantRepository tenants, TenantDatabases databases, ConversationFileRepository files,
                              FileStorage storage,
                              @Value("${app.chat.video-retention-hours:}") String videoRetentionHours,
                              @Value("${app.chat.file-retention-days:}") String fileRetentionDays) {
        this.tenants = tenants;
        this.databases = databases;
        this.files = files;
        this.storage = storage;
        this.videoRetention = Retention.parse("app.chat.video-retention-hours", videoRetentionHours, ChronoUnit.HOURS);
        this.fileRetention = Retention.parse("app.chat.file-retention-days", fileRetentionDays, ChronoUnit.DAYS);
        // Said at the start: an operator sees in the log what will be deleted - also that a retention left empty keeps.
        LOG.info("Chat files: {}", retentionNote(videoRetention, fileRetention));
    }

    static String retentionNote(Optional<Duration> videoRetention, Optional<Duration> fileRetention) {
        if (videoRetention.isEmpty() && fileRetention.isEmpty()) {
            return "kept - no retention set";
        }
        return "an uploaded video " + videoRetention.map(hours -> "deleted " + hours.toHours() + " hours after its upload")
                .orElse("kept") + ", every other file " + fileRetention.map(days -> "deleted " + days.toDays()
                + " days after it was stored").orElse("kept");
    }

    @Scheduled(cron = "${app.chat.cleanup-cron}")
    void run() {
        expire(Instant.now());
    }

    /**
     * Every tenant's files past their retention time, over a connection of its own per tenant, closed at once - through
     * the pools every tenant would keep a connection open for half a minute (docs/architecture/tenancy.md). A tenant
     * whose database fails is logged and tried at the next run.
     */
    void expire(Instant now) {
        if (videoRetention.isEmpty() && fileRetention.isEmpty()) {
            return;
        }
        for (Tenant tenant : tenants.findAll()) {
            try (Connection connection = databases.openConnection(tenant.databaseName())) {
                expire(connection, now);
            } catch (SQLException | RuntimeException e) {
                LOG.warn("Chat files of tenant {} could not be expired: {}", tenant.id(), e.toString());
            }
        }
    }

    private void expire(Connection connection, Instant now) {
        if (videoRetention.isPresent()) {
            expire(connection, files.findVideosToExpire(connection, now.minus(videoRetention.get())), now);
        }
        if (fileRetention.isPresent()) {
            expire(connection, files.findFilesToExpire(connection, now.minus(fileRetention.get())), now);
        }
    }

    /**
     * The content goes before the row is marked: a failure in between leaves a file to delete again, never a mark on one
     * still there. A file whose content cannot be deleted is tried at the next run - it holds up none after it.
     */
    private void expire(Connection connection, List<ConversationFile> expiring, Instant now) {
        for (ConversationFile file : expiring) {
            try {
                storage.deleteAll(file.storageDir());
            } catch (RuntimeException e) {
                LOG.warn("Chat file {} could not be deleted: {}", file.id(), e.toString());
                continue;
            }
            files.markExpired(connection, file.id(), now);
        }
    }
}
