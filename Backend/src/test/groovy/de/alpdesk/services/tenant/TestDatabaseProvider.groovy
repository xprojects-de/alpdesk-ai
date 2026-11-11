package de.alpdesk.services.tenant

import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.PgVectorContainerProvider

/**
 * The specs' PostgreSQL (application-test.properties, jdbc:tc:pgvectortest:...): Testcontainers' pgvector container
 * with 1 GB of shared memory instead of a container's 64 MB. PostgreSQL keeps its statistics of every database and
 * table in shared memory, and the ~1000 tenant databases a run creates outgrow 64 MB - late in the suite every new
 * tenant failed with "could not resize shared memory segment ... No space left on device". The JDBC URL cannot set it.
 */
class TestDatabaseProvider extends PgVectorContainerProvider {

    static final long SHARED_MEMORY_BYTES = 1024L * 1024 * 1024

    @Override
    boolean supports(String databaseType) {
        databaseType == "pgvectortest"
    }

    @Override
    JdbcDatabaseContainer newInstance(String tag) {
        super.newInstance(tag).withSharedMemorySize(SHARED_MEMORY_BYTES) as JdbcDatabaseContainer
    }
}
