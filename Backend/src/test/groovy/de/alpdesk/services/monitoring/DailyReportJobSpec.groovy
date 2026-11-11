package de.alpdesk.services.monitoring

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.mail.MailSender
import de.alpdesk.repositories.monitoring.DailyReportRepository
import de.alpdesk.repositories.monitoring.DailyReportRepository.AccessRequests
import de.alpdesk.repositories.monitoring.DailyReportRepository.SignInNeeded
import de.alpdesk.repositories.monitoring.DailyReportRepository.TenantActivity
import de.alpdesk.repositories.monitoring.DailyReportRepository.TenantRow
import de.alpdesk.repositories.monitoring.DailyReportRepository.Usage
import de.alpdesk.services.branding.Branding
import de.alpdesk.services.branding.BrandingService
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.context.ApplicationContext
import io.micronaut.context.exceptions.BeanInstantiationException
import io.micronaut.http.HttpStatus
import jakarta.mail.internet.MimeMessage
import spock.lang.Specification

import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant

/**
 * The daily report over real SMTP (GreenMail): that it always goes out, what each block says, what needs attention,
 * the report without the database, and what happens when it cannot be sent. The data comes from mocks -
 * DailyReportSpec covers the queries against real databases.
 */
class DailyReportJobSpec extends Specification {

    /** 06:00 in Berlin. */
    static final Instant NOW = Instant.parse("2026-10-03T04:00:00Z")
    static final Instant FROM = NOW - Duration.ofHours(24)
    static final Instant MONTH_START = Instant.parse("2026-09-30T22:00:00Z")
    static final Instant LONG_AGO = NOW - Duration.ofDays(3)

    GreenMail smtp = new GreenMail(ServerSetupTest.SMTP.dynamicPort())
    DailyReportRepository repository = Mock()
    TenantDatabases tenantDatabases = Mock()
    ApplicationLogService applicationLog = Mock()
    BrandingService branding = Mock()
    DailyReportMail mail = new DailyReportMail(branding, "Europe/Berlin")

    /*
     * What the process and the database hold, changed by the features: stubs given here would win over the features'
     * own (Spock matches the interactions in the order they were declared), so these read fields.
     */
    Instant startedAt = LONG_AGO
    List<RecentLogAppender.Entry> logEntries = []
    boolean logLost = false
    List<TenantRow> tenants = []
    Map<Long, Usage> usage = [:]
    Map<String, Long> sizes = ["alpdesk_ai": 50L * 1024 * 1024]
    AccessRequests accessRequests = new AccessRequests(0, null)

    def setup() {
        smtp.start()
        branding.installation() >> new Branding("Zirmo", "#49732a", true)
        applicationLog.startedAt() >> { startedAt }
        applicationLog.since(_) >> { logEntries }
        applicationLog.lostSince(_) >> { logLost }
        repository.databaseVersion() >> "18.3"
        repository.managementDatabase() >> "alpdesk_ai"
        repository.databaseSizes() >> { sizes }
        repository.activeTenants() >> { tenants }
        repository.usage(FROM, MONTH_START) >> { usage }
        repository.accessRequests() >> { accessRequests }
        repository.signInsNeeded() >> []
    }

    def cleanup() {
        smtp.stop()
    }

    def "the report goes out to every recipient also when everything is fine"() {
        when:
        job("ops@example.com, admin@example.com").send(NOW)

        then:
        def messages = received()
        messages*.allRecipients.flatten()*.toString() as Set == ["ops@example.com", "admin@example.com"] as Set
        messages[0].subject == "Zirmo: daily report 2026-10-03"
        def html = html(messages[0])
        html.contains("As of 2026-10-03 06:00. Over the last 24 hours: 0 active tenants, 0 questions, €0.00 on the global"
                + " keys; 0 errors and 0 warnings in the application log.")
        html.contains("Nothing - everything is fine.")
        rows(html).containsAll([["Version", "1.2.3"], ["Running since", "2026-09-30 06:00"],
                                ["PostgreSQL", "18.3 – reachable"], ["Management database", "50.0 MB"],
                                ["All databases of the app", "50.0 MB"]])
        rows(html).find { it[0] == "Disk of the stored files" }[1] == "50.0 GB free of 100.0 GB"
        html.contains("No active tenant.")
        html.contains("No warnings or errors.")
        !html.contains("restarted")
    }

    def "every active tenant is tabled with its data, its questions and its usage, with the totals"() {
        given:
        tenants = [new TenantRow(1, "Musterfirma", "tenant_a", 4), new TenantRow(2, "Beispiel AG", "tenant_b", 2)]
        sizes = ["alpdesk_ai": 1024L * 1024, "tenant_a": 3L * 1024 * 1024 * 1024, "tenant_b": 20L * 1024 * 1024]
        usage = [1L: new Usage(12, 0.5, 1.25, 10.004, 20.0)]
        Connection a = Mock()
        Connection b = Mock()
        tenantDatabases.openConnection("tenant_a") >> a
        tenantDatabases.openConnection("tenant_b") >> b
        repository.tenantActivity(a, FROM) >> new TenantActivity(120, 117, 1, NOW - Duration.ofMinutes(5), 2, 0, 7, 30, 3)
        repository.tenantActivity(b, FROM) >> new TenantActivity(0, 0, 0, null, 0, 0, 0, 4, 1)

        when:
        job().send(NOW)

        then:
        def html = html(received()[0])
        html.contains("Over the last 24 hours: 2 active tenants, 34 questions, €0.50 on the global keys;")
        rows(html).containsAll([
                ["All databases of the app", "3.0 GB"],
                ["Tenant", "Database", "Users", "Documents", "Ready", "Waiting", "Failed", "New (24 h)"],
                ["Musterfirma", "3.0 GB", "4", "120", "117", "1", "2", "7"],
                ["Beispiel AG", "20.0 MB", "2", "0", "0", "0", "0", "0"],
                ["Tenant", "Questions", "Users asking", "Model calls", "Cost 24 h", "Global keys 24 h", "Cost month", "Global keys month"],
                ["Musterfirma", "30", "3", "12", "€1.25", "€0.50", "€20.00", "€10.00"],
                ["Beispiel AG", "4", "1", "0", "€0.00", "€0.00", "€0.00", "€0.00"],
                ["Total", "34", "4", "12", "€1.25", "€0.50", "€20.00", "€10.00"]])
        and: "one document waiting for five minutes and old failures are no news"
        html.contains("Nothing - everything is fine.")
        and: "each tenant's connection is closed after reading it"
        1 * a.close()
        1 * b.close()
    }

    def "a tenant whose database cannot be read is reported as such - the others all the same"() {
        given:
        tenants = [new TenantRow(1, "Musterfirma", "tenant_a", 4), new TenantRow(2, "Beispiel AG", "tenant_b", 2)]
        tenantDatabases.openConnection("tenant_a") >> { throw new SQLException("database \"tenant_a\" does not exist") }
        Connection b = Mock()
        tenantDatabases.openConnection("tenant_b") >> b
        repository.tenantActivity(b, FROM) >> new TenantActivity(5, 5, 0, null, 0, 0, 0, 4, 1)

        when:
        job().send(NOW)

        then:
        received()[0].subject == "Zirmo: daily report 2026-10-03 – 1 point needs attention"
        def html = html(received()[0])
        html.contains("Musterfirma: its database could not be read (SQLException: database &quot;tenant_a&quot; does not exist).")
        rows(html).containsAll([["Musterfirma", "–", "4", "–", "–", "–", "–", "–"],
                                ["Beispiel AG", "–", "2", "5", "5", "0", "0", "0"],
                                ["Musterfirma", "–", "–", "0", "€0.00", "€0.00", "€0.00", "€0.00"]])
    }

    def "what needs attention: #situation"() {
        given:
        def tenant = new TenantRow(1, "Musterfirma", "tenant_a", 1)
        def report = new DailyReport(FROM, NOW, new DailyReport.Application("1.2.3", LONG_AGO, storage), emptyLog(),
                "18.3", 0, 0, [new DailyReport.TenantReport(tenant, 1, activity, null, Usage.NONE)], requests, signIns)

        expect:
        mail.attention(report) == points

        where:
        situation                          | storage                                | activity                                                         | requests | signIns || points
        "nothing"                          | disk(50, 100)                          | idle()                                                           | none() | [] || []
        "documents waiting over an hour"   | disk(50, 100)                          | new TenantActivity(9, 6, 3, NOW - Duration.ofMinutes(61), 0, 0, 3, 0, 0) | none() | [] || ["Musterfirma: 3 documents wait to be embedded, the first since 2026-10-03 04:59."]
        "a document waiting under an hour" | disk(50, 100)                          | new TenantActivity(1, 0, 1, NOW - Duration.ofMinutes(59), 0, 0, 1, 0, 0) | none() | [] || []
        "failed in the period"             | disk(50, 100)                          | new TenantActivity(3, 2, 0, null, 4, 1, 1, 0, 0)                 | none() | [] || ["Musterfirma: 1 document could not be embedded in the last 24 hours."]
        "failed before the period"         | disk(50, 100)                          | new TenantActivity(3, 2, 0, null, 1, 0, 0, 0, 0)                 | none() | [] || []
        "a low disk"                       | disk(9, 100)                           | idle()                                                           | none() | [] || ["Only 9.0 GB of 100.0 GB free on the disk of the stored files."]
        "an unmeasurable disk"             | null                                   | idle()                                                           | none() | [] || []
        "people waiting for access"        | disk(50, 100)                          | idle()                                                           | new AccessRequests(2, NOW - Duration.ofHours(30)) | [] || ["2 people wait for access (page \"Access requests\"), the first since 2026-10-02 00:00."]
        "a sign-in to repeat"              | disk(50, 100)                          | idle()                                                           | none()                                        | [new SignInNeeded("Musterfirma", "Ticketsystem")] || ["Musterfirma: the connected software Ticketsystem needs a sign-in again (page \"Software\")."]
    }

    def "the log block counts warnings and errors, names the loudest loggers and lists the newest entries"() {
        given:
        logEntries = [entry("ERROR", "de.alpdesk.services.document.DocumentIndexer", "Embedding failed", "java.lang.IllegalStateException: boom\n\tat x", 5),
                      entry("WARN", "de.alpdesk.services.document.DocumentIndexer", "Slow embedding", null, 10),
                      entry("WARN", "de.alpdesk.controller.common.GlobalExceptionHandler", "Request failed", null, 20)]

        when:
        job().send(NOW)

        then:
        def html = html(received()[0])
        html.contains("1 error and 2 warnings in the application log.")
        html.contains("1 error and 2 warnings.")
        rows(html).containsAll([["DocumentIndexer", "2", "Embedding failed"],
                                ["GlobalExceptionHandler", "1", "Request failed"],
                                ["10-03 05:55:00", "Error", "DocumentIndexer", "Embedding failed – java.lang.IllegalStateException: boom"],
                                ["10-03 05:50:00", "Warning", "DocumentIndexer", "Slow embedding"]])
    }

    def "a restart and a buffer that rolled over are pointed out"() {
        given:
        startedAt = NOW - Duration.ofHours(2)
        logLost = true
        logEntries = [entry("WARN", "de.alpdesk.Test", "something", null, 1)]

        when:
        job().send(NOW)

        then:
        def html = html(received()[0])
        rows(html).contains(["Running since", "2026-10-03 04:00 – restarted in the last 24 hours"])
        html.contains("Recorded since the restart at 2026-10-03 04:00")
        html.contains("The buffer rolled over")
    }

    def "a tenant's name and a log message are escaped"() {
        given:
        tenants = [new TenantRow(1, "<b>Muster & Co</b>", "tenant_a", 1)]
        Connection a = Mock()
        tenantDatabases.openConnection("tenant_a") >> a
        repository.tenantActivity(a, FROM) >> new TenantActivity(0, 0, 0, null, 0, 0, 0, 0, 0)
        logEntries = [entry("WARN", "de.alpdesk.Test", "<script>alert(1)</script>", null, 1)]

        when:
        job().send(NOW)

        then:
        def html = html(received()[0])
        html.contains("&lt;b&gt;Muster &amp; Co&lt;/b&gt;")
        html.contains("&lt;script&gt;alert(1)&lt;/script&gt;")
        !html.contains("<b>Muster")
        !html.contains("<script>")
    }

    def "without the database the report still goes out with what the process knows"() {
        given:
        logEntries = [entry("ERROR", "com.zaxxer.hikari.pool.HikariPool", "no connection", null, 1)]

        when:
        job().send(NOW)

        then:
        _ * repository.databaseVersion() >> { throw new IllegalStateException("read", new SQLException("Connection to localhost:5432 refused")) }
        def message = received()[0]
        message.subject == "Zirmo: daily report 2026-10-03 – database not readable"
        def html = html(message)
        html.contains("SQLException: Connection to localhost:5432 refused")
        rows(html).contains(["PostgreSQL", "not readable"])
        html.contains("no connection")
        html.contains(">System<")
        html.contains(">Application log<")
        !html.contains(">Tenants: data<")
    }

    def "a scheduled report that cannot be sent is given up until tomorrow"() {
        given:
        def job = job("ops@example.com", closedPort())

        when:
        job.send(NOW)

        then:
        noExceptionThrown()
        received().isEmpty()
    }

    def "sent at the admin's request it answers the number of recipients"() {
        expect:
        job("ops@example.com, admin@example.com").sendNow() == 2
        received().size() == 2
    }

    def "sent at the admin's request it is refused without #missing (409)"() {
        given:
        def job = new DailyReportJob(repository, tenantDatabases, applicationLog, mail, sender(host, smtp.smtp.port),
                recipients, "1.2.3", "build/test-storage", "Europe/Berlin")

        when:
        job.sendNow()

        then:
        def e = thrown(ServiceException)
        e.status() == HttpStatus.CONFLICT
        e.code() == "dailyReport.notConfigured"
        received().isEmpty()

        where:
        missing          | host        | recipients
        "a mail server"  | ""          | "ops@example.com"
        "recipients"     | "127.0.0.1" | " "
    }

    def "sent at the admin's request a refusing mail server is the answer (502)"() {
        when:
        job("ops@example.com", closedPort()).sendNow()

        then:
        def e = thrown(ServiceException)
        e.status() == HttpStatus.BAD_GATEWAY
        e.code() == "dailyReport.sendFailed"
    }

    def "a wrong mail setting stops the start, not the first report: #setting"() {
        when:
        ApplicationContext.run(settings, "test").close()

        then:
        def e = thrown(BeanInstantiationException)
        e.message.contains(message)

        where:
        setting           | settings                                                        || message
        "a recipient"     | ["app.monitoring.mail-to": "ops@example.com, kein-postfach"]   || "app.monitoring.mail-to: not an e-mail address: kein-postfach"
        "the sender"      | ["app.mail.host": "127.0.0.1", "app.mail.from": "kein-absender"] || "app.mail.from (or app.mail.username) must be an e-mail address, not 'kein-absender'"
    }

    def "sizes and amounts are written readably: #bytes bytes"() {
        expect:
        DailyReportMail.bytes(bytes) == text

        where:
        bytes                       || text
        0                           || "0 B"
        1023                        || "1023 B"
        1024                        || "1.0 KB"
        1536L * 1024                || "1.5 MB"
        5L * 1024 * 1024 * 1024 * 1024 * 2 || "10.0 TB"
    }

    /** With a disk of its own - the machine's real one may be missing or full. */
    private DailyReportJob job(String recipients = "ops@example.com", int port = smtp.smtp.port) {
        new DailyReportJob(repository, tenantDatabases, applicationLog, mail, sender("127.0.0.1", port), recipients,
                "1.2.3", "build/test-storage", "Europe/Berlin") {
            @Override
            DailyReport.Storage storage() {
                disk(50, 100)
            }
        }
    }

    private static MailSender sender(String host, int port) {
        new MailSender(host, port, "NONE", "", "", "report@example.com")
    }

    /** A port nothing listens on. */
    private static int closedPort() {
        def socket = new ServerSocket(0)
        int port = socket.localPort
        socket.close()
        port
    }

    private static DailyReport.Storage disk(long freeGb, long totalGb) {
        new DailyReport.Storage("/data", freeGb * 1024 * 1024 * 1024, totalGb * 1024 * 1024 * 1024)
    }

    private static TenantActivity idle() {
        new TenantActivity(0, 0, 0, null, 0, 0, 0, 0, 0)
    }

    private static AccessRequests none() {
        new AccessRequests(0, null)
    }

    private static DailyReport.LogSummary emptyLog() {
        new DailyReport.LogSummary(0, 0, [], [], false)
    }

    /** A log entry {@code minutesAgo} before NOW. */
    private static RecentLogAppender.Entry entry(String level, String logger, String message, String stackTrace, int minutesAgo) {
        new RecentLogAppender.Entry(NOW - Duration.ofMinutes(minutesAgo), level, logger, "main", message, stackTrace)
    }

    private List<MimeMessage> received() {
        smtp.receivedMessages as List
    }

    private static String html(MimeMessage message) {
        message.content as String
    }

    /** The table rows of the mail as lists of cell texts (tags stripped, entities of the test data decoded). */
    private static List<List<String>> rows(String html) {
        (html =~ /(?s)<tr>(.*?)<\/tr>/).collect { match ->
            (match[1] =~ /(?s)<td[^>]*>(.*?)<\/td>/).collect { cell ->
                cell[1].replaceAll(/<[^>]+>/, "").replace("&amp;", "&").trim()
            }
        }
    }
}
