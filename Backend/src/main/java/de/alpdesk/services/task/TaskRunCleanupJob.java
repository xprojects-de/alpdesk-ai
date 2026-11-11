package de.alpdesk.services.task;

import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.task.TaskRepository;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.Retention;
import de.alpdesk.services.tenant.TenantDatabases;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Deletes the scheduled tasks' runs app.task.run-retention-days after they ended (docs/architecture/tasks.md "Runs"):
 * every run keeps the lines its steps read, and a task reading logs twice a day piles them up for ever otherwise. A run
 * still open is never deleted, and the read positions stay - they belong to the task. Left empty, runs are kept until
 * a tenant admin deletes them or their task.
 * <p>
 * Built at start ({@code @Context}), like the chat files' cleanup: a wrong retention stops the start, and the log says
 * at once what will be deleted.
 */
@Context
public class TaskRunCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(TaskRunCleanupJob.class);

    private final TenantRepository tenants;
    private final TaskRepository tasks;
    private final TenantDatabases databases;
    private final TaskRunRepository runs;
    private final Optional<Duration> retention;

    public TaskRunCleanupJob(TenantRepository tenants, TaskRepository tasks, TenantDatabases databases,
                             TaskRunRepository runs, @Value("${app.task.run-retention-days:}") String retentionDays) {
        this.tenants = tenants;
        this.tasks = tasks;
        this.databases = databases;
        this.runs = runs;
        this.retention = Retention.parse("app.task.run-retention-days", retentionDays, ChronoUnit.DAYS);
        LOG.info("Task runs: {}", retention.map(days -> "deleted " + days.toDays() + " days after they ended")
                .orElse("kept - no retention set"));
    }

    @Scheduled(cron = "0 40 * * * *")
    void run() {
        deleteEnded(Instant.now());
    }

    /**
     * The runs past their retention of every tenant with tasks, over a connection of its own per tenant, closed at once
     * - through the pools every tenant would keep a connection open (docs/architecture/tenancy.md). A tenant whose
     * database fails is logged and tried at the next run.
     */
    void deleteEnded(Instant now) {
        if (retention.isEmpty()) {
            return;
        }
        Set<Long> withTasks = new HashSet<>(tasks.findTenantIdsWithTasks());
        for (Tenant tenant : tenants.findAll()) {
            if (!withTasks.contains(tenant.id())) {
                continue;
            }
            try (Connection connection = databases.openConnection(tenant.databaseName())) {
                int deleted = runs.deleteEndedBefore(connection, now.minus(retention.get()));
                if (deleted > 0) {
                    LOG.info("Deleted {} task run(s) of tenant {} past their retention", deleted, tenant.id());
                }
            } catch (SQLException | RuntimeException e) {
                LOG.warn("Task runs of tenant {} could not be deleted: {}", tenant.id(), e.toString());
            }
        }
    }
}
