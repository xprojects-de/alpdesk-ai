package de.alpdesk.services.monitoring;

import de.alpdesk.logging.RecentLogAppender;
import de.alpdesk.mail.MailTemplate;
import de.alpdesk.mail.MailTemplate.Html;
import de.alpdesk.repositories.monitoring.DailyReportRepository;
import de.alpdesk.repositories.monitoring.DailyReportRepository.AccessRequests;
import de.alpdesk.repositories.monitoring.DailyReportRepository.TenantActivity;
import de.alpdesk.repositories.monitoring.DailyReportRepository.Usage;
import de.alpdesk.services.branding.Branding;
import de.alpdesk.services.branding.BrandingService;
import de.alpdesk.services.monitoring.DailyReport.Application;
import de.alpdesk.services.monitoring.DailyReport.LogSource;
import de.alpdesk.services.monitoring.DailyReport.LogSummary;
import de.alpdesk.services.monitoring.DailyReport.Storage;
import de.alpdesk.services.monitoring.DailyReport.TenantReport;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link DailyReport} into the e-mail to the operator (English, like everything the backend writes): one
 * section per block, mostly small tables. It holds only the texts and which value goes where; the layout is in the
 * building blocks under {@code resources/mail/}, the header in the installation's name and colour (branding.md).
 * <p>
 * Nothing of a tenant's content goes into it - no document or file names, no questions, no names of people: counts,
 * sizes and amounts only, and the log lines, which carry no content either (GlobalExceptionHandler).
 */
@Singleton
public class DailyReportMail {

    /** A finished e-mail. */
    public record Mail(String subject, String html) {
    }

    /** Documents waiting longer than this point to an embedding that stands still. */
    static final Duration WAITING_TOO_LONG = Duration.ofHours(1);
    /** Less free space than this share of the disk needs attention. */
    static final double LOW_DISK_SHARE = 0.10;

    private static final String NORMAL = "#111827";
    private static final String MUTED = "#6b7280";
    private static final String GOOD = "#15803d";
    private static final String WARNING = "#b45309";
    private static final String ERROR = "#b91c1c";
    private static final int MAX_TEXT = 300;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private static final MailTemplate PAGE = MailTemplate.load("page");
    private static final MailTemplate DETAIL = MailTemplate.load("detail");
    private static final MailTemplate SECTION = MailTemplate.load("report-section");
    private static final MailTemplate SUBTITLE = MailTemplate.load("report-subtitle");
    private static final MailTemplate LINE = MailTemplate.load("report-line");
    private static final MailTemplate TABLE = MailTemplate.load("report-table");
    private static final MailTemplate ROW = MailTemplate.load("report-row");
    private static final MailTemplate HEAD = MailTemplate.load("report-head");
    private static final MailTemplate CELL = MailTemplate.load("report-cell");
    private static final Html FOOTER = MailTemplate.load("footer-report").fill(Map.of());

    /** A table cell: its text, its colour, whether it is a number (right-aligned) and whether it may wrap. */
    private record Cell(String text, String color, boolean number, boolean wrap) {

        static Cell text(String text) {
            return new Cell(text, NORMAL, false, true);
        }

        static Cell text(String text, String color) {
            return new Cell(text, color, false, true);
        }

        /** A short value that must not break inside a word (a time, a level, a logger name). */
        static Cell label(String text, String color) {
            return new Cell(text, color, false, false);
        }

        static Cell number(String text) {
            return new Cell(text, NORMAL, true, false);
        }

        static Cell number(long value) {
            return number(String.valueOf(value));
        }

        static Cell number(long value, String colorIfNotZero) {
            return new Cell(String.valueOf(value), value == 0 ? NORMAL : colorIfNotZero, true, false);
        }
    }

    private final BrandingService branding;
    private final ZoneId zone;

    public DailyReportMail(BrandingService branding, @Value("${app.timezone}") String timezone) {
        this.branding = branding;
        this.zone = ZoneId.of(timezone);
    }

    public Mail render(DailyReport report) {
        List<String> attention = attention(report);
        String subject = subject(report.to())
                + (attention.isEmpty() ? "" : " – " + plural(attention.size(), "point needs", "points need") + " attention");
        int questions = 0;
        BigDecimal billed = BigDecimal.ZERO;
        for (TenantReport tenant : report.tenants()) {
            questions += tenant.activity() == null ? 0 : tenant.activity().questions();
            billed = billed.add(tenant.usage().billed());
        }
        String summary = "As of " + time(report.to()) + ". Over the last 24 hours: "
                + plural(report.tenants().size(), "active tenant", "active tenants") + ", "
                + plural(questions, "question", "questions") + ", " + euros(billed) + " on the global keys; "
                + plural(report.log().errors(), "error", "errors") + " and "
                + plural(report.log().warnings(), "warning", "warnings") + " in the application log.";
        Html content = Html.join(List.of(
                section("Needs attention", attentionList(attention)),
                section("System", system(report.application(), report.databaseVersion(), report.from(),
                        report.managementDatabaseSize(), report.allDatabasesSize())),
                section("Tenants: data", tenantData(report.tenants())),
                section("Tenants: questions and usage", tenantUsage(report.tenants())),
                section("Application log", log(report.log(), report.application(), report.from()))));
        return page(subject, summary, content);
    }

    /**
     * The report when the management database cannot be read: what the process knows without it.
     *
     * @param cause the technical reason, shown as it is
     */
    public Mail renderUnreadable(Instant from, Instant to, Application application, LogSummary log, String cause) {
        String subject = subject(to) + " – database not readable";
        String summary = "As of " + time(to) + ". The database could not be read - it is probably out of reach. As long"
                + " as that lasts, nobody can sign in or chat. The report holds only what the application knows without it.";
        Html content = Html.join(List.of(
                DETAIL.fill(Map.of("detail", cause)),
                section("System", system(application, null, from, 0, 0)),
                section("Application log", log(log, application, from))));
        return page(subject, summary, content);
    }

    /**
     * What a person should look at: a full disk, embeddings standing still or failing, unreadable tenant databases,
     * connected software whose sign-in has to be repeated, waiting people.
     */
    List<String> attention(DailyReport report) {
        List<String> points = new ArrayList<>();
        Storage storage = report.application().storage();
        if (storage != null && lowDisk(storage)) {
            points.add("Only " + bytes(storage.usableBytes()) + " of " + bytes(storage.totalBytes())
                    + " free on the disk of the stored files.");
        }
        for (TenantReport tenant : report.tenants()) {
            String name = tenant.tenant().name();
            if (tenant.unreadable() != null) {
                points.add(name + ": its database could not be read (" + tenant.unreadable() + ").");
                continue;
            }
            TenantActivity activity = tenant.activity();
            if (activity.waitingSince() != null && activity.waitingSince().isBefore(report.to().minus(WAITING_TOO_LONG))) {
                points.add(name + ": " + plural(activity.waiting(), "document waits", "documents wait")
                        + " to be embedded, the first since " + time(activity.waitingSince()) + ".");
            }
            if (activity.failedInPeriod() > 0) {
                points.add(name + ": " + plural(activity.failedInPeriod(), "document", "documents")
                        + " could not be embedded in the last 24 hours.");
            }
        }
        for (DailyReportRepository.SignInNeeded connection : report.signInsNeeded()) {
            points.add(connection.tenant() + ": the connected software " + connection.connection()
                    + " needs a sign-in again (page \"Software\").");
        }
        AccessRequests requests = report.accessRequests();
        if (requests.count() > 0) {
            points.add(plural(requests.count(), "person waits", "people wait") + " for access (page \"Access requests\"),"
                    + " the first since " + time(requests.oldest()) + ".");
        }
        return points;
    }

    private Html attentionList(List<String> attention) {
        if (attention.isEmpty()) {
            return line("Nothing - everything is fine.", GOOD);
        }
        List<Html> lines = new ArrayList<>();
        for (String point : attention) {
            lines.add(line(point, ERROR));
        }
        return Html.join(lines);
    }

    private Mail page(String subject, String summary, Html content) {
        Branding installation = branding.installation();
        Html page = PAGE.fill(Map.of("subject", subject, "installation", installation.name(), "brand", installation.primaryColor(),
                "title", "Daily report", "summary", summary, "content", content, "footer", FOOTER));
        return new Mail(subject, page.value());
    }

    private String subject(Instant to) {
        return branding.installation().name() + ": daily report " + DAY.format(to.atZone(zone));
    }

    /** @param databaseVersion null: the database could not be read (and its sizes are unknown) */
    private Html system(Application application, @Nullable String databaseVersion, Instant from, long managementSize,
                        long allSize) {
        boolean restarted = application.startedAt().isAfter(from);
        Cell started = restarted
                ? Cell.text(time(application.startedAt()) + " – restarted in the last 24 hours", WARNING)
                : Cell.text(time(application.startedAt()));
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(List.of(Cell.text("Version"), Cell.text(application.version())));
        rows.add(List.of(Cell.text("Running since"), started));
        if (databaseVersion == null) {
            rows.add(List.of(Cell.text("PostgreSQL"), Cell.text("not readable", ERROR)));
        } else {
            rows.add(List.of(Cell.text("PostgreSQL"), Cell.text(databaseVersion + " – reachable", GOOD)));
            rows.add(List.of(Cell.text("Management database"), Cell.text(bytes(managementSize))));
            rows.add(List.of(Cell.text("All databases of the app"), Cell.text(bytes(allSize))));
        }
        rows.add(List.of(Cell.text("Disk of the stored files"), storage(application.storage())));
        return table(List.of(), rows);
    }

    private static Cell storage(@Nullable Storage storage) {
        if (storage == null) {
            return Cell.text("not measurable", WARNING);
        }
        String text = bytes(storage.usableBytes()) + " free of " + bytes(storage.totalBytes());
        return Cell.text(text, lowDisk(storage) ? ERROR : NORMAL);
    }

    private Html tenantData(List<TenantReport> tenants) {
        if (tenants.isEmpty()) {
            return line("No active tenant.", MUTED);
        }
        List<List<Cell>> rows = new ArrayList<>();
        for (TenantReport tenant : tenants) {
            Cell size = tenant.databaseSize() == null ? Cell.number("–") : Cell.number(bytes(tenant.databaseSize()));
            TenantActivity a = tenant.activity();
            if (a == null) {
                rows.add(List.of(Cell.text(tenant.tenant().name()), size, Cell.number(tenant.tenant().users()),
                        Cell.number("–"), Cell.number("–"), Cell.number("–"), Cell.number("–"), Cell.number("–")));
                continue;
            }
            rows.add(List.of(Cell.text(tenant.tenant().name()), size, Cell.number(tenant.tenant().users()),
                    Cell.number(a.documents()), Cell.number(a.ready()), Cell.number(a.waiting(), WARNING),
                    Cell.number(a.failed(), ERROR), Cell.number(a.added())));
        }
        return table(List.of("Tenant", "Database", "Users", "Documents", "Ready", "Waiting", "Failed", "New (24 h)"), rows);
    }

    private Html tenantUsage(List<TenantReport> tenants) {
        if (tenants.isEmpty()) {
            return line("No active tenant.", MUTED);
        }
        List<List<Cell>> rows = new ArrayList<>();
        int questions = 0;
        int askers = 0;
        int calls = 0;
        Usage total = Usage.NONE;
        for (TenantReport tenant : tenants) {
            TenantActivity a = tenant.activity();
            Usage u = tenant.usage();
            rows.add(List.of(Cell.text(tenant.tenant().name()),
                    a == null ? Cell.number("–") : Cell.number(a.questions()),
                    a == null ? Cell.number("–") : Cell.number(a.askers()),
                    Cell.number(u.calls()), Cell.number(euros(u.amount())), Cell.number(euros(u.billed())),
                    Cell.number(euros(u.monthAmount())), Cell.number(euros(u.monthBilled()))));
            questions += a == null ? 0 : a.questions();
            askers += a == null ? 0 : a.askers();
            calls += u.calls();
            total = new Usage(0, total.billed().add(u.billed()), total.amount().add(u.amount()),
                    total.monthBilled().add(u.monthBilled()), total.monthAmount().add(u.monthAmount()));
        }
        rows.add(List.of(Cell.label("Total", NORMAL), Cell.number(questions), Cell.number(askers), Cell.number(calls),
                Cell.number(euros(total.amount())), Cell.number(euros(total.billed())),
                Cell.number(euros(total.monthAmount())), Cell.number(euros(total.monthBilled()))));
        List<Html> parts = new ArrayList<>();
        parts.add(table(List.of("Tenant", "Questions", "Users asking", "Model calls", "Cost 24 h", "Global keys 24 h",
                "Cost month", "Global keys month"), rows));
        parts.add(line("Questions of stored conversations - an API key without history stores none, its calls count in"
                + " the model calls. \"Global keys\" is the part the admin's keys paid, which is billed.", MUTED));
        return Html.join(parts);
    }

    private Html log(LogSummary log, Application application, Instant from) {
        List<Html> parts = new ArrayList<>();
        if (log.warnings() == 0 && log.errors() == 0) {
            parts.add(line("No warnings or errors.", GOOD));
        } else {
            parts.add(line(plural(log.errors(), "error", "errors") + " and " + plural(log.warnings(), "warning", "warnings") + ".",
                    log.errors() > 0 ? ERROR : WARNING));
        }
        if (application.startedAt().isAfter(from)) {
            parts.add(line("Recorded since the restart at " + time(application.startedAt())
                    + " - older entries are only in the container log of the server.", MUTED));
        }
        if (log.lost()) {
            parts.add(line("The buffer rolled over - older entries of this period are only in the container log of the"
                    + " server.", WARNING));
        }
        if (log.latest().isEmpty()) {
            return Html.join(parts);
        }
        List<List<Cell>> sources = new ArrayList<>();
        for (LogSource source : log.sources()) {
            sources.add(List.of(Cell.label(shortLogger(source.logger()), NORMAL), Cell.number(source.count()),
                    Cell.text(cap(source.newestMessage()))));
        }
        parts.add(SUBTITLE.fill(Map.of("text", "Most frequent sources")));
        parts.add(table(List.of("Logger", "Count", "Newest message"), sources));

        List<List<Cell>> latest = new ArrayList<>();
        for (RecentLogAppender.Entry entry : log.latest()) {
            boolean error = "ERROR".equals(entry.level());
            latest.add(List.of(Cell.label(LOG_TIME.format(entry.timestamp().atZone(zone)), NORMAL),
                    error ? Cell.label("Error", ERROR) : Cell.label("Warning", WARNING),
                    Cell.label(shortLogger(entry.logger()), NORMAL), Cell.text(logText(entry))));
        }
        parts.add(SUBTITLE.fill(Map.of("text", "Newest entries")));
        parts.add(table(List.of("Time", "Level", "Logger", "Message"), latest));
        return Html.join(parts);
    }

    /** The message and the exception's first line ("java.lang.IllegalStateException: ..."), if there is one. */
    private static String logText(RecentLogAppender.Entry entry) {
        String text = cap(entry.message());
        if (entry.stackTrace() == null) {
            return text;
        }
        return text + " – " + cap(entry.stackTrace().lines().findFirst().orElse(""));
    }

    private Html section(String title, Html content) {
        return SECTION.fill(Map.of("title", title, "brand", branding.installation().primaryColor(), "content", content));
    }

    private static Html line(String text, String color) {
        return LINE.fill(Map.of("text", text, "color", color));
    }

    /** A table; without headers it has no head row (a list of label and value). */
    private static Html table(List<String> headers, List<List<Cell>> rows) {
        List<Html> lines = new ArrayList<>();
        if (!headers.isEmpty()) {
            List<Html> heads = new ArrayList<>();
            for (int i = 0; i < headers.size(); i++) {
                boolean number = !rows.isEmpty() && rows.getFirst().get(i).number();
                heads.add(HEAD.fill(Map.of("text", headers.get(i), "align", number ? "right" : "left")));
            }
            lines.add(ROW.fill(Map.of("cells", Html.join(heads))));
        }
        for (List<Cell> row : rows) {
            List<Html> cells = new ArrayList<>();
            for (Cell cell : row) {
                cells.add(CELL.fill(Map.of("text", cell.text(), "color", cell.color(), "align", cell.number() ? "right" : "left",
                        "nowrap", cell.wrap() ? "" : "white-space:nowrap;")));
            }
            lines.add(ROW.fill(Map.of("cells", Html.join(cells))));
        }
        return TABLE.fill(Map.of("rows", Html.join(lines)));
    }

    private String time(Instant time) {
        return TIME.format(time.atZone(zone));
    }

    private static boolean lowDisk(Storage storage) {
        return storage.usableBytes() < storage.totalBytes() * LOW_DISK_SHARE;
    }

    static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    static String euros(BigDecimal amount) {
        return "€" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private static String shortLogger(String logger) {
        return logger.substring(logger.lastIndexOf('.') + 1);
    }

    private static String cap(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "…";
    }
}
