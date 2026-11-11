package de.alpdesk.repositories.document;

import de.alpdesk.entities.document.KnowledgeSource;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The knowledge sources (management database). A run writes only its outcome and a save only the settings - never the
 * whole row, so neither overwrites what the other wrote meanwhile.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface KnowledgeSourceRepository extends CrudRepository<KnowledgeSource, Long> {

    List<KnowledgeSource> findByTenantIdOrderByName(Long tenantId);

    Optional<KnowledgeSource> findByIdAndTenantId(Long id, Long tenantId);

    int deleteByIdAndTenantId(Long id, Long tenantId);

    /** The tenant's own sources only: another tenant's store id must not reveal that tenant's source names. */
    @Query("SELECT name FROM knowledge_source WHERE file_store_id = :storeId AND tenant_id = :tenantId ORDER BY name")
    List<String> findNamesReadingStore(Long storeId, Long tenantId);

    @Query("SELECT name FROM knowledge_source WHERE area_id = :areaId AND tenant_id = :tenantId ORDER BY name")
    List<String> findNamesWritingArea(Long areaId, Long tenantId);

    /** The active sources whose schedule is due, of every tenant - the scheduler decides per tenant. */
    @Query("SELECT * FROM knowledge_source WHERE active AND next_run_at <= :now ORDER BY next_run_at")
    List<KnowledgeSource> findDue(Instant now);

    /** Only if the source is still due then - a save meanwhile computed its own next time. */
    @Query("UPDATE knowledge_source SET next_run_at = :next WHERE id = :id AND next_run_at = :due")
    int moveNextRun(Long id, Instant due, @Nullable Instant next);

    @Query("""
            UPDATE knowledge_source SET name = :name, file_store_id = :fileStoreId, folder = :folder, area_id = :areaId,
            pattern = :pattern, schedule = :schedule, active = :active, next_run_at = :nextRunAt, updated_at = :updatedAt
            WHERE id = :id""")
    void updateSettings(Long id, String name, Long fileStoreId, String folder, @Nullable Long areaId,
                        @Nullable String pattern, String schedule, boolean active, @Nullable Instant nextRunAt,
                        Instant updatedAt);

    @Query("UPDATE knowledge_source SET last_started_at = :startedAt WHERE id = :id")
    void recordStart(Long id, Instant startedAt);

    /**
     * A run's outcome. succeededAt, openFiles and openBytes null: a run that failed keeps the last success and what was
     * open before.
     */
    @Query("""
            UPDATE knowledge_source SET last_finished_at = :finishedAt, last_error = :error, last_added = :added,
            last_changed = :changed, last_deleted = :deleted, last_refused = :refused, last_skipped = :skipped,
            last_succeeded_at = COALESCE(CAST(:succeededAt AS TIMESTAMPTZ), last_succeeded_at),
            open_files = COALESCE(CAST(:openFiles AS INTEGER), open_files),
            open_bytes = COALESCE(CAST(:openBytes AS BIGINT), open_bytes)
            WHERE id = :id""")
    void recordRun(Long id, Instant finishedAt, @Nullable String error, int added, int changed, int deleted, int refused,
                   int skipped, @Nullable Instant succeededAt, @Nullable Integer openFiles, @Nullable Long openBytes);
}
