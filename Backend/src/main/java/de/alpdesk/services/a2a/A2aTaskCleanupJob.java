package de.alpdesk.services.a2a;

import de.alpdesk.services.common.Retention;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Deletes the ended A2A tasks of the tenants' assistant once app.a2a.task-retention-hours passed (docs/architecture/a2a.md
 * "Tasks"): answers kept for machines, fetched soon. Only the tenants with such tasks are visited - never every tenant
 * (docs/architecture/tenancy.md). Empty keeps them. Built at start: a wrong setting stops it.
 */
@Context
public class A2aTaskCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(A2aTaskCleanupJob.class);

    private final JdbcTaskStore store;
    private final Optional<Duration> retention;

    public A2aTaskCleanupJob(JdbcTaskStore store, @Value("${app.a2a.task-retention-hours:}") String retentionHours) {
        this.store = store;
        this.retention = Retention.parse("app.a2a.task-retention-hours", retentionHours, ChronoUnit.HOURS);
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "10m")
    void run() {
        expire(Instant.now());
    }

    /** A tenant whose database fails is logged and tried at the next run. */
    void expire(Instant now) {
        if (retention.isEmpty()) {
            return;
        }
        for (Long tenantId : store.tenantsWithTasks()) {
            try {
                int deleted = store.deleteChangedBefore(tenantId, now.minus(retention.get()));
                if (deleted > 0) {
                    LOG.info("Deleted {} A2A tasks of tenant {}", deleted, tenantId);
                }
            } catch (RuntimeException e) {
                LOG.warn("A2A tasks of tenant {} could not be deleted: {}", tenantId, e.toString());
            }
        }
    }
}
