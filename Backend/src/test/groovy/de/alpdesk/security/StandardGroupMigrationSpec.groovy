package de.alpdesk.security

import de.alpdesk.services.ai.AiApi
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import io.micronaut.transaction.TransactionOperations

import java.sql.Connection

/**
 * The block of V17 that gives the tenants of before their group "Standard" (access needs a group, 7 November 2026) -
 * run again here on tenants made for it: one without the group gets it, empty; one with it keeps its own.
 */
@MicronautTest(transactional = false)
class StandardGroupMigrationSpec extends Specification {

    static final String MARKER = "-- Access needs a group (decided 7 November 2026"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    TransactionOperations<Connection> transactions

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
    }

    def "a tenant of before gets an empty group Standard - one that has it keeps it, nothing doubled"() {
        given: "a tenant as before 2.0.0 - without Standard - with a user, and one that has its Standard with a member"
        def before = api.tenant()
        assert api.status(api.adminToken, "DELETE", "/admin/tenants/${before.id}/groups/${api.standardGroup(before.id)}") ==
                io.micronaut.http.HttpStatus.NO_CONTENT
        api.user(before.id, true)
        def current = api.tenant()
        def member = api.user(current.id)

        when:
        execute(migrationBlock())

        then:
        def groups = api.admin("GET", "/admin/tenants/${before.id}/groups") as List<Map>
        groups*.name == ["Standard"]
        groups[0].userIds == []

        and:
        def kept = api.admin("GET", "/admin/tenants/${current.id}/groups") as List<Map>
        kept*.name == ["Standard"]
        kept[0].userIds == [member.id]
    }

    private static String migrationBlock() {
        String migration = StandardGroupMigrationSpec.getResourceAsStream("/db/migration/V17__release_2_0_0.sql").text
        int start = migration.indexOf(MARKER)
        assert start >= 0: "the block is no longer in V17"
        int end = migration.indexOf(";", start)
        migration.substring(start, end + 1)
    }

    /** On the management database, in a transaction of its own - as Flyway runs the migration. */
    private void execute(String sql) {
        transactions.executeWrite { status ->
            status.connection.createStatement().withCloseable { it.execute(sql) }
        }
    }
}
