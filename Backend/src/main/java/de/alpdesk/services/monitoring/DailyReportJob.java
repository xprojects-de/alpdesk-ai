package de.alpdesk.services.monitoring;

import de.alpdesk.logging.RecentLogAppender;
import de.alpdesk.mail.MailSender;
import de.alpdesk.repositories.monitoring.DailyReportRepository;
import de.alpdesk.repositories.monitoring.DailyReportRepository.TenantActivity;
import de.alpdesk.repositories.monitoring.DailyReportRepository.TenantRow;
import de.alpdesk.repositories.monitoring.DailyReportRepository.Usage;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.monitoring.DailyReport.Application;
import de.alpdesk.services.monitoring.DailyReport.LogSource;
import de.alpdesk.services.monitoring.DailyReport.LogSummary;
import de.alpdesk.services.monitoring.DailyReport.Storage;
import de.alpdesk.services.monitoring.DailyReport.TenantReport;
import de.alpdesk.services.monitoring.DailyReportMail.Mail;
import de.alpdesk.services.tenant.TenantDatabases;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mails the daily report to {@code app.monitoring.mail-to} - every morning ({@code app.monitoring.daily-report-cron}),
 * also when everything is fine: a report that does not come means the process or the mail server is down, which the
 * process itself cannot tell. It covers the last 24 hours: the process, the databases and the disk, every active
 * tenant (its data, questions and usage), what needs attention, and the application log.
 * <p>
 * When the management database cannot be read, the report still goes out with what the process knows without it -
 * its version and start, the disk and the application log - and says so. A report that cannot be sent is not repeated:
 * the next one goes out the next morning. The admin sends one at once with {@link #sendNow} - a test of the mail
 * settings.
 * <p>
 * Built at start ({@code @Context}), not when the scheduler first runs it: a wrong recipient or sender address must
 * stop the start, not silently fail every report from the next morning on.
 */
@Context
public class DailyReportJob {

    private static final Logger LOG = LoggerFactory.getLogger(DailyReportJob.class);
    static final Duration PERIOD = Duration.ofHours(24);
    static final int LOG_SOURCES = 10;
    static final int LOG_LATEST = 20;
    private static final int MAX_CAUSE_LENGTH = 300;

    private final DailyReportRepository repository;
    private final TenantDatabases tenantDatabases;
    private final ApplicationLogService applicationLog;
    private final DailyReportMail mail;
    private final MailSender sender;
    private final List<String> recipients;
    private final String version;
    private final String storagePath;
    private final ZoneId zone;

    public DailyReportJob(DailyReportRepository repository, TenantDatabases tenantDatabases,
                          ApplicationLogService applicationLog, DailyReportMail mail, MailSender sender,
                          @Value("${app.monitoring.mail-to:}") String recipients,
                          @Value("${app.version}") String version,
                          @Value("${app.storage.local.path}") String storagePath,
                          @Value("${app.timezone}") String timezone) {
        this.repository = repository;
        this.tenantDatabases = tenantDatabases;
        this.applicationLog = applicationLog;
        this.mail = mail;
        this.sender = sender;
        this.recipients = recipients(recipients);
        this.version = version;
        this.storagePath = storagePath;
        this.zone = ZoneId.of(timezone);
    }

    /** Its own scheduler, so a slow SMTP server holds up no other job. */
    @Scheduled(cron = "${app.monitoring.daily-report-cron:0 0 6 * * *}", zoneId = "${app.timezone}", scheduler = "monitoring")
    void run() {
        send(Instant.now());
    }

    /** The scheduled report: a report that cannot be sent is logged, the next one goes out tomorrow. */
    void send(Instant now) {
        if (!configured()) {
            return;
        }
        try {
            deliver(build(now));
        } catch (MessagingException | RuntimeException e) {
            LOG.warn("Could not send the daily report ({}) - the next one goes out tomorrow", e.toString());
        }
    }

    /**
     * Sends the report now, at the admin's request - to try the mail settings. Not in a transaction: it reads every
     * tenant database and waits for the mail server.
     *
     * @return to how many recipients it went
     */
    public int sendNow() {
        if (!configured()) {
            throw ServiceException.conflict("dailyReport.notConfigured",
                    "No mail can be sent: set the mail server (APP_MAIL_HOST) and the recipients (APP_MONITORING_MAIL_TO)");
        }
        try {
            deliver(build(Instant.now()));
        } catch (MessagingException e) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "dailyReport.sendFailed",
                    "The mail server refused the report: " + e.getMessage(), Map.of("reason", String.valueOf(e.getMessage())));
        }
        return recipients.size();
    }

    private boolean configured() {
        return sender.enabled() && !recipients.isEmpty();
    }

    /** The report, or - when the management database cannot be read - the report of what is known without it. */
    private Mail build(Instant now) {
        Instant from = now.minus(PERIOD);
        Application application = new Application(version, applicationLog.startedAt(), storage());
        LogSummary log = logSummary(from);
        DailyReport report;
        try {
            report = collect(from, now, application, log);
        } catch (RuntimeException e) {
            LOG.warn("The daily report could not read the database ({}) - sending what is known without it", e.toString());
            return mail.renderUnreadable(from, now, application, log, rootCause(e));
        }
        return mail.render(report);
    }

    private void deliver(Mail message) throws MessagingException {
        sender.send(recipients, message.subject(), message.html());
        LOG.info("Daily report sent to {} recipient(s)", recipients.size());
    }

    private DailyReport collect(Instant from, Instant now, Application application, LogSummary log) {
        String databaseVersion = repository.databaseVersion();
        Map<String, Long> sizes = repository.databaseSizes();
        Instant monthStart = LocalDate.ofInstant(now, zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
        Map<Long, Usage> usage = repository.usage(from, monthStart);
        List<TenantReport> tenants = new ArrayList<>();
        for (TenantRow tenant : repository.activeTenants()) {
            tenants.add(tenantReport(tenant, sizes.get(tenant.databaseName()), usage.getOrDefault(tenant.id(), Usage.NONE), from));
        }
        long allSizes = sizes.values().stream().mapToLong(Long::longValue).sum();
        return new DailyReport(from, now, application, log, databaseVersion,
                sizes.getOrDefault(repository.managementDatabase(), 0L), allSizes, tenants, repository.accessRequests(),
                repository.signInsNeeded());
    }

    /**
     * Through a connection of its own, closed at once - not the tenant's pool, which would keep one open for every
     * tenant. A tenant database that cannot be read is reported as such - the other tenants are reported all the same.
     */
    private TenantReport tenantReport(TenantRow tenant, @Nullable Long databaseSize, Usage usage, Instant from) {
        try (Connection connection = tenantDatabases.openConnection(tenant.databaseName())) {
            TenantActivity activity = repository.tenantActivity(connection, from);
            return new TenantReport(tenant, databaseSize, activity, null, usage);
        } catch (SQLException | RuntimeException e) {
            LOG.warn("The daily report could not read the database of tenant {} ({})", tenant.id(), e.toString());
            return new TenantReport(tenant, databaseSize, null, rootCause(e), usage);
        }
    }

    /** The disk of the stored files; null when the path does not exist (yet) or cannot be measured. */
    @Nullable
    Storage storage() {
        File path = new File(storagePath).getAbsoluteFile();
        long total = path.getTotalSpace();
        return total == 0 ? null : new Storage(path.getPath(), path.getUsableSpace(), total);
    }

    /** The period's warnings and errors: counted, grouped by logger (most first), and the newest ones. */
    LogSummary logSummary(Instant from) {
        List<RecentLogAppender.Entry> entries = applicationLog.since(from);
        int errors = 0;
        Map<String, List<RecentLogAppender.Entry>> byLogger = new LinkedHashMap<>();
        for (RecentLogAppender.Entry entry : entries) {
            if ("ERROR".equals(entry.level())) {
                errors++;
            }
            byLogger.computeIfAbsent(entry.logger(), _ -> new ArrayList<>()).add(entry);
        }
        List<LogSource> sources = new ArrayList<>();
        for (Map.Entry<String, List<RecentLogAppender.Entry>> logger : byLogger.entrySet()) {
            List<RecentLogAppender.Entry> ofLogger = logger.getValue();
            sources.add(new LogSource(logger.getKey(), ofLogger.size(), ofLogger.getFirst().message()));
        }
        sources.sort(Comparator.comparingInt(LogSource::count).reversed());
        return new LogSummary(entries.size() - errors, errors,
                sources.subList(0, Math.min(LOG_SOURCES, sources.size())),
                entries.subList(0, Math.min(LOG_LATEST, entries.size())),
                applicationLog.lostSince(from));
    }

    /** The innermost cause's class and first line - a database error's message names no row (logServerErrorDetail). */
    static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? "" : ": " + cause.getMessage().lines().findFirst().orElse("");
        String text = cause.getClass().getSimpleName() + message;
        return text.length() > MAX_CAUSE_LENGTH ? text.substring(0, MAX_CAUSE_LENGTH) + "..." : text;
    }

    /** Checked at startup: a wrong address would fail every report later. */
    static List<String> recipients(String configured) {
        List<String> recipients = Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String recipient : recipients) {
            try {
                new InternetAddress(recipient, true);
            } catch (AddressException e) {
                throw new IllegalArgumentException("app.monitoring.mail-to: not an e-mail address: " + recipient, e);
            }
        }
        return recipients;
    }
}
