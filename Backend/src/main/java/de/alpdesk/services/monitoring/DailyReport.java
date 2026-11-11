package de.alpdesk.services.monitoring;

import de.alpdesk.logging.RecentLogAppender;
import de.alpdesk.repositories.monitoring.DailyReportRepository.AccessRequests;
import de.alpdesk.repositories.monitoring.DailyReportRepository.SignInNeeded;
import de.alpdesk.repositories.monitoring.DailyReportRepository.TenantActivity;
import de.alpdesk.repositories.monitoring.DailyReportRepository.TenantRow;
import de.alpdesk.repositories.monitoring.DailyReportRepository.Usage;
import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.util.List;

/** What the daily report says about the period {@code from} - {@code to}, collected by {@link DailyReportJob}. */
public record DailyReport(Instant from, Instant to, Application application, LogSummary log, String databaseVersion,
                          long managementDatabaseSize, long allDatabasesSize, List<TenantReport> tenants,
                          AccessRequests accessRequests, List<SignInNeeded> signInsNeeded) {

    /** The process itself - known without the database. {@code storage} null: its disk could not be measured. */
    public record Application(String version, Instant startedAt, @Nullable Storage storage) {
    }

    /** The disk the stored files (documents, chat files) are on. */
    public record Storage(String path, long usableBytes, long totalBytes) {
    }

    /**
     * An active tenant. When its database cannot be read, {@code activity} and {@code databaseSize} are null and
     * {@code unreadable} says why - the other tenants are reported all the same.
     */
    public record TenantReport(TenantRow tenant, @Nullable Long databaseSize, @Nullable TenantActivity activity,
                               @Nullable String unreadable, Usage usage) {
    }

    /**
     * The warnings and errors of the period from the in-memory log buffer - known without the database.
     *
     * @param sources the loggers that logged most, with the newest message of each
     * @param latest  the newest entries
     * @param lost    the buffer rolled over within the period: older entries are only in the container log
     */
    public record LogSummary(int warnings, int errors, List<LogSource> sources, List<RecentLogAppender.Entry> latest,
                             boolean lost) {
    }

    public record LogSource(String logger, int count, String newestMessage) {
    }
}
