package de.alpdesk.services.tenant;

import de.alpdesk.repositories.tenant.TenantRepository;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings every tenant's database to the current tenant schema on start - also creating one that is missing, e.g.
 * when the process stopped between creating a tenant and its database. A tenant whose database fails is logged and
 * skipped: the others must still start. The full-text index of its table is the indexer's, after the server's
 * start ({@code DocumentIndexer}).
 * <p>
 * It runs when the context has started, before the HTTP server binds its port ({@link StartupEvent}, not
 * {@code ServerStartupEvent}): no request and no taken-up embedding ({@code DocumentIndexer}, on the server's start)
 * reaches a tenant database still on the previous schema.
 * <p>
 * Always on in an installation. Only the specs switch it off ({@code app.tenant-databases.migrate-on-start=false} in
 * application-test.properties): every spec class starts a context of its own, the tenants of the whole suite pile up
 * in one database server, and each start would migrate all of them again - though each was migrated when it was
 * created. The specs of the migration itself switch it on.
 */
@Singleton
@Requires(property = "app.tenant-databases.migrate-on-start", value = "true", defaultValue = "true")
public class TenantDatabaseMigration implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(TenantDatabaseMigration.class);

    private final TenantRepository repository;
    private final TenantDatabases databases;

    public TenantDatabaseMigration(TenantRepository repository, TenantDatabases databases) {
        this.repository = repository;
        this.databases = databases;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        repository.findAllOrderByName().forEach(tenant -> {
            try {
                databases.ensure(tenant.databaseName());
            } catch (RuntimeException e) {
                LOG.error("Database {} of tenant {} could not be migrated", tenant.databaseName(), tenant.id(), e);
            }
        });
    }
}
