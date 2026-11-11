package de.alpdesk.services.monitoring

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import de.alpdesk.repositories.monitoring.DailyReportRepository
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import jakarta.mail.internet.MimeMessage
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * The daily report's queries against real databases - a tenant's own and the management database - and one report end
 * to end over SMTP (GreenMail, started before the context so its port goes into the settings). What each block says
 * and what needs attention is in DailyReportJobSpec; the tenant key's 403 in TenantApiKeySpec.
 */
@MicronautTest(transactional = false)
class DailyReportSpec extends Specification implements TestPropertyProvider {

    @Shared
    GreenMail smtp = new GreenMail(ServerSetupTest.SMTP.dynamicPort())

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    DailyReportRepository repository

    @Inject
    TenantDatabases tenantDatabases

    @Inject
    TenantRepository tenants

    /** Through the pool itself: outside a transaction the injected DataSource hands out no connection. */
    DataSource managementDatabase

    AiApi api

    @Override
    Map<String, String> getProperties() {
        smtp.start()
        // The report reads every active tenant's database - in the full suite the specs before have made hundreds.
        ["micronaut.http.client.read-timeout": "5m",
         "app.mail.host"         : "127.0.0.1",
         "app.mail.port"         : String.valueOf(smtp.smtp.port),
         "app.mail.security"     : "NONE",
         "app.mail.from"         : "report@example.com",
         "app.monitoring.mail-to": "ops@example.com, admin@example.com"]
    }

    @Inject
    void setManagementDatabase(DataSource dataSource) {
        managementDatabase = DelegatingDataSource.unwrapDataSource(dataSource)
    }

    def setup() {
        api = new AiApi(client)
        smtp.purgeEmailFromAllMailboxes()
    }

    def cleanupSpec() {
        smtp.stop()
    }

    def "a tenant's database: documents by status, the longest waiting, failures and new ones, questions and who asked"() {
        given:
        def now = Instant.now()
        def since = now - Duration.ofHours(24)
        def tenant = api.tenant()
        def database = tenantDatabase(tenant)
        document(database, "READY", now - Duration.ofDays(3), now - Duration.ofDays(3))
        document(database, "READY", now - Duration.ofHours(2), now - Duration.ofHours(1))
        document(database, "PENDING", now - Duration.ofMinutes(30), now - Duration.ofMinutes(30))
        document(database, "PROCESSING", now - Duration.ofDays(2), now - Duration.ofHours(3))
        document(database, "FAILED", now - Duration.ofDays(5), now - Duration.ofDays(5))
        document(database, "FAILED", now - Duration.ofDays(2), now - Duration.ofHours(5))
        def first = conversation(database, 7)
        message(database, first, "USER", now - Duration.ofHours(1))
        message(database, first, "ASSISTANT", now - Duration.ofHours(1))
        message(database, first, "USER", now - Duration.ofDays(2))
        message(database, conversation(database, 8), "USER", now - Duration.ofHours(3))
        message(database, conversation(database, 7), "USER", now - Duration.ofHours(4))
        and: "a conversation of the client software, which has no user"
        message(database, conversation(database, null), "USER", now - Duration.ofHours(1))

        when:
        def activity = read(tenant, since)

        then:
        activity.documents() == 6
        activity.ready() == 2
        activity.waiting() == 2
        Duration.between(activity.waitingSince(), now - Duration.ofHours(3)).abs() < Duration.ofSeconds(1)
        activity.failed() == 2
        activity.failedInPeriod() == 1
        activity.added() == 2
        activity.questions() == 4
        activity.askers() == 2
    }

    def "an empty tenant database has nothing waiting"() {
        when:
        def activity = read(api.tenant(), Instant.now() - Duration.ofHours(24))

        then:
        activity == new DailyReportRepository.TenantActivity(0, 0, 0, null, 0, 0, 0, 0, 0)
    }

    def "a tenant's usage: calls in the period, the cost and the global keys' part - in the period and in the month"() {
        given:
        def now = Instant.now()
        def tenant = api.tenant(true)
        def other = api.tenant(true)
        usage(tenant.id, "OWN", 1.0, now - Duration.ofHours(1))
        usage(tenant.id, "GLOBAL", 0.5, now - Duration.ofHours(2))
        usage(tenant.id, "GLOBAL", 2.0, now - Duration.ofDays(5))
        usage(tenant.id, "OWN", 100.0, now - Duration.ofDays(20))
        usage(other.id, "GLOBAL", 7.0, now - Duration.ofHours(1))

        when:
        def usage = repository.usage(now - Duration.ofHours(24), now - Duration.ofDays(10))

        then:
        with(usage[tenant.id as Long]) {
            calls() == 2
            billed() == 0.5
            amount() == 1.5
            monthBilled() == 2.5
            monthAmount() == 3.5
        }
        usage[other.id as Long].billed() == 7.0
    }

    def "only active tenants count, with their active users; every database has its size"() {
        given:
        def tenant = api.tenant()
        api.user(tenant.id)
        def leaving = api.user(tenant.id)
        api.admin("PUT", "/admin/tenants/${tenant.id}/users/${leaving.id}",
                [username: leaving.username, displayName: "Maria", active: false])
        def inactive = api.tenant()
        api.admin("PUT", "/admin/tenants/${inactive.id}", [name: inactive.name, active: false])

        when:
        def active = repository.activeTenants()
        def sizes = repository.databaseSizes()

        then:
        def row = active.find { it.id() == tenant.id as Long }
        row.name() == tenant.name
        row.users() == 1
        active.every { it.id() != inactive.id as Long }
        sizes[row.databaseName()] > 0
        sizes[repository.managementDatabase()] > 0
        repository.databaseVersion() ==~ /\d+(\.\d+)*/
    }

    def "only the app's own databases are measured - not another application's on the same server"() {
        given: "the database of another application on a shared PostgreSQL"
        def foreign = "fremde_anwendung_" + AiApi.unique().replaceAll(/[^a-z0-9]/, "")
        update(managementDatabase, "CREATE DATABASE \"${foreign}\"")
        and: "an inactive tenant, whose database is still the app's"
        def inactive = api.tenant()
        api.admin("PUT", "/admin/tenants/${inactive.id}", [name: inactive.name, active: false])

        when:
        def sizes = repository.databaseSizes()

        then:
        !sizes.containsKey(foreign)
        !sizes.containsKey("postgres")
        sizes[tenantName(inactive)] > 0
        sizes[repository.managementDatabase()] > 0

        cleanup:
        update(managementDatabase, "DROP DATABASE IF EXISTS \"${foreign}\"")
    }

    def "people waiting for access are counted with the oldest request"() {
        given:
        def before = repository.accessRequests()
        def requestedAt = Instant.parse("2026-01-02T03:04:05Z")
        def id = insert(managementDatabase, "INSERT INTO access_request (username, display_name, requested_at) VALUES (?, ?, ?) RETURNING id",
                "wartend-" + AiApi.unique(), "Maria", Timestamp.from(requestedAt))

        when:
        def requests = repository.accessRequests()

        then:
        requests.count() == before.count() + 1
        !requests.oldest().isAfter(requestedAt)

        cleanup:
        update(managementDatabase, "DELETE FROM access_request WHERE id = ?", id)
    }

    def "the admin sends the report now - with every active tenant, to every recipient"() {
        given:
        def tenant = api.tenant()
        api.user(tenant.id)

        when:
        def response = api.admin("POST", "/admin/daily-report/send", [:])

        then:
        response == [recipients: 2]
        def messages = smtp.receivedMessages as List<MimeMessage>
        messages*.allRecipients.flatten()*.toString() as Set == ["ops@example.com", "admin@example.com"] as Set
        messages[0].subject.startsWith("Zirmo: daily report ")
        def html = messages[0].content as String
        html.contains(">System<")
        def row = rows(html).find { it[0] == tenant.name && it.size() == 8 && it[2] == "1" }
        assert row != null
        row[1] ==~ /\d+\.\d (KB|MB)/
        row.subList(3, 8) == ["0", "0", "0", "0", "0"]
        and: "it keeps no connection to the tenant's database open - hundreds of tenants would take the server's last ones"
        openConnections(tenantName(tenant)) == 0
    }

    def "only the admin sends the report"() {
        given:
        def tenant = api.tenant()

        expect:
        api.status(api.tenantAdminToken(tenant.id), "POST", "/admin/daily-report/send") == HttpStatus.FORBIDDEN
        api.status(null, "POST", "/admin/daily-report/send") == HttpStatus.UNAUTHORIZED
        smtp.receivedMessages.length == 0
    }

    private String tenantName(Map tenant) {
        tenants.findById(tenant.id as Long).orElseThrow().databaseName()
    }

    private DataSource tenantDatabase(Map tenant) {
        tenantDatabases.dataSource(tenantName(tenant))
    }

    private DailyReportRepository.TenantActivity read(Map tenant, Instant since) {
        tenantDatabases.openConnection(tenantName(tenant)).withCloseable { repository.tenantActivity(it, since) }
    }

    private int openConnections(String databaseName) {
        insert(managementDatabase, "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = ?", databaseName) as int
    }

    private static void document(DataSource database, String status, Instant createdAt, Instant updatedAt) {
        update(database, "INSERT INTO document (file_name, size_bytes, text, status, origin, created_at, updated_at) "
                + "VALUES ('handbuch.txt', 10, 'Text', ?, 'UPLOAD', ?, ?)", status, Timestamp.from(createdAt), Timestamp.from(updatedAt))
    }

    private static long conversation(DataSource database, Long userId) {
        def now = Timestamp.from(Instant.now())
        insert(database, "INSERT INTO conversation (user_id, title, created_at, updated_at) VALUES (?, 'Frage', ?, ?) RETURNING id",
                userId, now, now)
    }

    private static void message(DataSource database, long conversationId, String role, Instant createdAt) {
        update(database, "INSERT INTO message (conversation_id, role, content, created_at) VALUES (?, ?, 'Text', ?)",
                conversationId, role, Timestamp.from(createdAt))
    }

    private void usage(Object tenantId, String keySource, BigDecimal amount, Instant createdAt) {
        update(managementDatabase, "INSERT INTO usage_record (tenant_id, provider, model_name, kind, key_source, input_tokens, "
                + "output_tokens, estimated, amount, created_at) VALUES (?, 'OPENAI', 'chat', 'CHAT', ?, 10, 10, false, ?, ?)",
                tenantId as Long, keySource, amount, Timestamp.from(createdAt))
    }

    private static long insert(DataSource database, String sql, Object... parameters) {
        database.connection.withCloseable { connection ->
            def statement = connection.prepareStatement(sql)
            parameters.eachWithIndex { value, i -> statement.setObject(i + 1, value) }
            def result = statement.executeQuery()
            result.next()
            result.getLong(1)
        }
    }

    private static void update(DataSource database, String sql, Object... parameters) {
        database.connection.withCloseable { connection ->
            def statement = connection.prepareStatement(sql)
            parameters.eachWithIndex { value, i -> statement.setObject(i + 1, value) }
            statement.executeUpdate()
        }
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
