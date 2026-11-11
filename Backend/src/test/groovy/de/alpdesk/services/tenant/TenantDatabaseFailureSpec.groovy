package de.alpdesk.services.tenant

import de.alpdesk.dto.tenant.TenantRequest
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.common.ServiceException
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpStatus
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import spock.lang.Specification

import javax.sql.DataSource

/**
 * A tenant whose database cannot be created is removed again and the admin is told to try again (503) - also when
 * the half-made database cannot be dropped either (the server out of reach): no tenant without a database, no 500. A
 * tenant whose database cannot be dropped is deleted all the same.
 */
@MicronautTest(transactional = false)
@Property(name = "spec.name", value = "TenantDatabaseFailureSpec")
class TenantDatabaseFailureSpec extends Specification {

    @Inject
    TenantService tenantService

    @Inject
    TenantRepository tenants

    @Inject
    FailingTenantDatabases databases

    def "a tenant whose database cannot be created is removed again - 503 'try again'#also"() {
        given:
        databases.failCreate = true
        databases.failDrop = failDrop
        def name = "Firma " + UUID.randomUUID()

        when:
        tenantService.create(new TenantRequest(name, null, null, null, null, null, null, null, null, null))

        then:
        def e = thrown(ServiceException)
        e.status() == HttpStatus.SERVICE_UNAVAILABLE
        e.message == "The tenant's database could not be created - please try again."
        tenants.findByNameIgnoreCase(name).isEmpty()

        cleanup:
        databases.failCreate = false
        databases.failDrop = false

        where:
        failDrop | also
        false    | ""
        true     | " - also when it cannot be dropped"
    }

    def "a tenant whose database is created but cannot be migrated is removed again with the half-made database"() {
        given:
        databases.failMigrate = true
        def name = "Firma " + UUID.randomUUID()

        when:
        tenantService.create(new TenantRequest(name, null, null, null, null, null, null, null, null, null))

        then:
        def e = thrown(ServiceException)
        e.status() == HttpStatus.SERVICE_UNAVAILABLE
        tenants.findByNameIgnoreCase(name).isEmpty()
        !databases.exists(databases.lastMigrated)

        cleanup:
        databases.failMigrate = false
    }

    def "a tenant whose database cannot be dropped is deleted all the same - the database stays behind unused"() {
        given:
        def name = "Firma " + UUID.randomUUID()
        def tenant = tenantService.create(new TenantRequest(name, null, null, null, null, null, null, null, null, null))
        databases.failDrop = true

        when:
        def deleted = tenantService.delete(tenant.id())

        then:
        deleted
        tenants.findById(tenant.id()).isEmpty()
        databases.exists(tenant.databaseName())

        when: "the same name is given to a new tenant"
        def again = tenantService.create(new TenantRequest(name, null, null, null, null, null, null, null, null, null))

        then:
        again.databaseName() != tenant.databaseName()

        cleanup:
        databases.failDrop = false
        databases.drop(tenant.databaseName())
        if (again != null) {
            tenantService.delete(again.id())
        }
    }

    /** The real databases, failing on demand. */
    @Singleton
    @Replaces(TenantDatabases)
    @Requires(property = "spec.name", value = "TenantDatabaseFailureSpec")
    static class FailingTenantDatabases extends TenantDatabases {

        volatile boolean failCreate
        volatile boolean failDrop
        volatile boolean failMigrate
        volatile String lastMigrated

        FailingTenantDatabases(DataSource managementDataSource,
                               @Value('${datasources.default.username}') String username,
                               @Value('${datasources.default.password}') String password,
                               @Value('${datasources.default.data-source-properties.options}') String connectionOptions) {
            super(managementDataSource, username, password, connectionOptions)
        }

        @Override
        void ensure(String databaseName) {
            if (failCreate) {
                throw new IllegalStateException("CREATE DATABASE failed")
            }
            super.ensure(databaseName)
        }

        @Override
        protected void migrate(String databaseName) {
            lastMigrated = databaseName
            if (failMigrate) {
                throw new IllegalStateException("The migration failed")
            }
            super.migrate(databaseName)
        }

        @Override
        void drop(String databaseName) {
            if (failDrop) {
                throw new IllegalStateException("DROP DATABASE failed")
            }
            super.drop(databaseName)
        }
    }
}
