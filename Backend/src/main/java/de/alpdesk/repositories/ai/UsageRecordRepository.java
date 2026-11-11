package de.alpdesk.repositories.ai;

import de.alpdesk.entities.ai.UsageRecord;
import de.alpdesk.entities.ai.UsageSummary;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UsageRecordRepository extends CrudRepository<UsageRecord, Long> {

    /** The usage per tenant, model and key source in [from, to) - of one tenant, or of all when tenantId is null. */
    @Query("""
            SELECT u.tenant_id, t.name AS tenant_name, u.provider, u.model_name, u.kind, u.key_source,
                   COUNT(*) AS calls, SUM(u.input_tokens) AS input_tokens, SUM(u.output_tokens) AS output_tokens,
                   SUM(u.cache_read_tokens) AS cache_read_tokens, SUM(u.cache_write_tokens) AS cache_write_tokens,
                   BOOL_OR(u.estimated) AS estimated, SUM(u.amount) AS amount
            FROM usage_record u JOIN tenant t ON t.id = u.tenant_id
            WHERE u.created_at >= :from AND u.created_at < :to
              AND (CAST(:tenantId AS BIGINT) IS NULL OR u.tenant_id = :tenantId)
            GROUP BY u.tenant_id, t.name, u.provider, u.model_name, u.kind, u.key_source
            ORDER BY t.name, u.key_source, u.model_name""")
    List<UsageSummary> summarize(@Nullable Long tenantId, Instant from, Instant to);
}
