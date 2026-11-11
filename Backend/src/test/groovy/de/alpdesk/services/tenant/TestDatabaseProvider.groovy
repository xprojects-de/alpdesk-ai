package de.alpdesk.services.tenant

import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.PgVectorContainerProvider

/**
 * The specs' PostgreSQL (application-test.properties, jdbc:tc:pgvectortest:...): Testcontainers' pgvector container
 * with 1 GB of shared memory instead of a container's 64 MB. PostgreSQL keeps its statistics of every database and
 * table in shared memory, and the ~1000 tenant databases a run creates outgrow 64 MB - late in the suite every new
 * tenant failed with "could not resize shared memory segment ... No space left on device". The JDBC URL cannot set it.
 * <p>
 * And with 300 connections instead of PostgreSQL's 100: a spec creates a tenant per feature, and each tenant's pool
 * keeps the connections it used for half a minute and more (TenantDatabases). A spec of 40 quick features with task
 * runs reached 70 connections on its own and, late in the suite, the limit - "sorry, too many clients already" failed
 * every new tenant.
 */
class TestDatabaseProvider extends PgVectorContainerProvider {

    static final long SHARED_MEMORY_BYTES = 1024L * 1024 * 1024
    static final int MAX_CONNECTIONS = 300

    @Override
    boolean supports(String databaseType) {
        databaseType == "pgvectortest"
    }

    @Override
    JdbcDatabaseContainer newInstance(String tag) {
        // The container's own command (postgres -c fsync=off) with the connections added - withCommand replaces it.
        super.newInstance(tag)
                .withSharedMemorySize(SHARED_MEMORY_BYTES)
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + MAX_CONNECTIONS) as JdbcDatabaseContainer
    }
}
