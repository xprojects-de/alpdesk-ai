package de.alpdesk.repositories.a2a;

import de.alpdesk.entities.a2a.A2aTaskIndex;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface A2aTaskIndexRepository extends CrudRepository<A2aTaskIndex, String> {

    /** The tenants with A2A tasks - the cleanup walks these only. */
    @Query("SELECT DISTINCT tenant_id FROM a2a_task_index")
    List<Long> findTenantIds();

    /**
     * The tenant's oldest index rows from before the time, a batch of them - for the cleanup, which looks which of
     * them have no task.
     */
    @Query("SELECT task_id FROM a2a_task_index WHERE tenant_id = :tenantId AND created_at < :before "
            + "ORDER BY created_at LIMIT 5000")
    List<String> findTaskIdsCreatedBefore(Long tenantId, Instant before);

    void deleteByTaskIdIn(List<String> taskIds);
}
