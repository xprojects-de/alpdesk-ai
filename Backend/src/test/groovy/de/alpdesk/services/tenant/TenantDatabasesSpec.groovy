package de.alpdesk.services.tenant

import de.alpdesk.dto.tenant.TenantRequest
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.document.DocumentIndexer
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.sql.SQLException
import javax.sql.DataSource

/**
 * Every tenant has a database of its own: created and migrated with the tenant, dropped with it, repaired on start. The
 * migration on start is off in the specs (application-test.properties) - also in this one's context: it walks every
 * tenant the suite has created so far, which takes a minute or more late in the suite. The one feature that needs it
 * starts a context of its own with it on, as an installation does.
 */
@MicronautTest(transactional = false)
class TenantDatabasesSpec extends Specification {

    @Inject
    ApplicationContext context

    @Inject
    TenantService tenantService

    @Inject
    TenantDatabases databases

    @Inject
    DataSource management

    @Inject
    DocumentIndexer indexer

    def "creating a tenant creates its database with pgvector, deleting it drops the database"() {
        when:
        def tenant = tenantService.create(new TenantRequest("Firma " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))

        then:
        tenant.databaseName() ==~ /tenant_[a-z0-9]{16}/
        databases.exists(tenant.databaseName())
        extensions(tenant.databaseName()).contains("vector")
        migrations(tenant.databaseName()) == ["1", "2", "3", "4", "5", "6", "7", "8"]

        when:
        tenantService.delete(tenant.id())

        then:
        !databases.exists(tenant.databaseName())
    }

    def "deleting a tenant closes its pool"() {
        given:
        def tenant = tenantService.create(new TenantRequest("Firma " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))
        migrations(tenant.databaseName())

        expect:
        databases.@pools.containsKey(tenant.databaseName())

        when:
        tenantService.delete(tenant.id())

        then:
        !databases.@pools.containsKey(tenant.databaseName())
    }

    def "two tenants never share a database"() {
        when:
        def first = tenantService.create(new TenantRequest("Firma " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))
        def second = tenantService.create(new TenantRequest("Firma " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))

        then:
        first.databaseName() != second.databaseName()
    }

    def "the start opens no pool of a tenant - also when it builds the tenant's full-text index"() {
        given: "a tenant's table with the full-text index of before, its pool closed"
        def tenant = tenantService.create(new TenantRequest("Firma " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))
        databases.openConnection(tenant.databaseName()).withCloseable { connection ->
            connection.createStatement().execute("CREATE TABLE embedding (embedding_id UUID PRIMARY KEY, "
                    + "embedding vector(3), text TEXT, metadata JSONB)")
            connection.createStatement().execute("CREATE INDEX embedding_text_idx ON embedding "
                    + "USING gin (to_tsvector('german', coalesce(text, '')))")
        }
        databases.@pools.remove(tenant.databaseName())?.close()

        when: "on the server's start every tenant is checked - a pool each would take the server's last connections"
        indexer.onApplicationEvent(null)

        then: "the walk over every tenant of the specs' database queues one task each - generous"
        new PollingConditions(timeout: 60).eventually {
            assert textIndexes(tenant.databaseName()) == ["embedding_text_simple_v2_idx"]
        }
        !databases.@pools.containsKey(tenant.databaseName())
    }

    def "on start a tenant's missing database is created again - when the context starts, before a server takes requests"() {
        given:
        def tenant = tenantService.create(new TenantRequest("Firma " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))
        databases.drop(tenant.databaseName())

        when: "a context starts without an HTTP server - with the migration on start, as in every installation"
        ApplicationContext.run(["app.tenant-databases.migrate-on-start": "true"], "test").close()

        then:
        databases.exists(tenant.databaseName())
        extensions(tenant.databaseName()).contains("vector")
        migrations(tenant.databaseName()) == ["1", "2", "3", "4", "5", "6", "7", "8"]
    }

    def "on start a tenant whose database fails is skipped - the tenants after it are still migrated, the start goes on"() {
        given: "two tenants, the first by name with a database that cannot be migrated"
        def broken = tenantService.create(new TenantRequest("AAA kaputt " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))
        def healthy = tenantService.create(new TenantRequest("ZZZ heil " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))
        def failingDatabases = Mock(TenantDatabases)
        def migration = new TenantDatabaseMigration(context.getBean(TenantRepository), failingDatabases)

        when:
        migration.onApplicationEvent(null)

        then: "no exception stops the start"
        noExceptionThrown()

        and:
        1 * failingDatabases.ensure(broken.databaseName()) >> { throw new IllegalStateException("migration failed") }
        1 * failingDatabases.ensure(healthy.databaseName())
    }

    def "an installation always migrates on start - only the specs switch it off"() {
        expect: "on unless switched off"
        TenantDatabaseMigration.getAnnotation(Requires).defaultValue() == "true"

        and: "switched off in the specs' contexts - on, every spec class's start would walk the suite's tenants again"
        context.findBean(TenantDatabaseMigration).isEmpty()
    }

    def "a migration failing on a tenant's data names none of the row's values - its message goes to the log"() {
        given:
        def tenant = tenantService.create(new TenantRequest("Firma " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))

        when: "a statement of a migration meets a row that is already there"
        databases.migrationDataSource(tenant.databaseName()).connection.withCloseable { connection ->
            connection.createStatement().execute("CREATE TABLE customer (name TEXT UNIQUE)")
            connection.createStatement().execute("INSERT INTO customer VALUES ('Musterfirma-4711')")
            connection.createStatement().execute("INSERT INTO customer VALUES ('Musterfirma-4711')")
        }

        then:
        def e = thrown(SQLException)
        e.SQLState == "23505"
        !e.message.contains("Musterfirma-4711")

        cleanup:
        tenantService.delete(tenant.id())
    }

    def "a failing statement names none of the row's values - through a tenant's pool and the management database's alike"() {
        given: "what an exception's message says ends up in the log, with every exception logged with its causes"
        def tenant = tenantService.create(new TenantRequest("Firma " + UUID.randomUUID(), null, null, null, null, null, null, null, null, null))

        when:
        def messages = [databases.dataSource(tenant.databaseName()), DelegatingDataSource.unwrapDataSource(management)].collect { source ->
            source.connection.withCloseable { connection ->
                connection.createStatement().execute("CREATE TEMP TABLE review_customer (name TEXT UNIQUE)")
                try {
                    connection.createStatement().execute("INSERT INTO review_customer VALUES ('Musterfirma-4711')")
                    connection.createStatement().execute("INSERT INTO review_customer VALUES ('Musterfirma-4711')")
                    "no failure"
                } catch (SQLException e) {
                    e.message
                } finally {
                    connection.createStatement().execute("DROP TABLE review_customer")
                }
            }
        }

        then:
        messages.size() == 2
        messages.every { it.contains("review_customer_name_key") && !it.contains("Musterfirma-4711") }

        cleanup:
        tenantService.delete(tenant.id())
    }

    def "a tenant database is reached like the management database - with its URL's parameters"() {
        expect:
        TenantDatabases.tenantUrl(managementUrl, "tenant_abc") == tenantUrl

        where:
        managementUrl                                                           | tenantUrl
        "jdbc:postgresql://db:5432/alpdesk_ai"                                  | "jdbc:postgresql://db:5432/tenant_abc"
        "jdbc:postgresql://db:5432/alpdesk_ai?sslmode=verify-full&sslrootcert=/certs/ca.pem" | "jdbc:postgresql://db:5432/tenant_abc?sslmode=verify-full&sslrootcert=/certs/ca.pem"
        "jdbc:postgresql://localhost/alpdesk_ai?"                               | "jdbc:postgresql://localhost/tenant_abc?"
    }

    def "a management database that is not PostgreSQL has no tenant databases"() {
        when:
        TenantDatabases.tenantUrl("jdbc:h2:mem:alpdesk_ai", "tenant_abc")

        then:
        thrown(IllegalStateException)
    }

    def "only generated names reach the SQL"() {
        when:
        databases.ensure('tenant_x"; DROP DATABASE alpdesk_ai_test; --')

        then:
        thrown(IllegalArgumentException)
    }

    private List<String> extensions(String databaseName) {
        query(databaseName, "SELECT extname FROM pg_extension")
    }

    private List<String> migrations(String databaseName) {
        query(databaseName, "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
    }

    private List<String> query(String databaseName, String sql) {
        databases.dataSource(databaseName).connection.withCloseable { connection ->
            def result = connection.createStatement().executeQuery(sql)
            def values = []
            while (result.next()) {
                values << result.getString(1)
            }
            values
        }
    }

    private List<String> textIndexes(String databaseName) {
        databases.openConnection(databaseName).withCloseable { connection ->
            def result = connection.createStatement().executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'embedding' AND indexname LIKE 'embedding_text%'")
            def names = []
            while (result.next()) {
                names << result.getString(1)
            }
            names
        }
    }
}
