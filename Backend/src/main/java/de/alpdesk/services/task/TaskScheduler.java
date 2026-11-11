package de.alpdesk.services.task;

import de.alpdesk.entities.task.StartedBy;
import de.alpdesk.entities.task.Task;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.task.TaskRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Starts the tasks whose schedule is due (docs/architecture/tasks.md "Schedule"), once a minute over every tenant. A
 * task still running is skipped and logged; a tenant that is switched off or may not have tasks starts nothing. Either
 * way the task's next time moves on - so turning a tenant on again does not make up for the times in between, and a
 * downtime is made up for by one run. One installation, no cluster: no locking.
 */
@Singleton
public class TaskScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(TaskScheduler.class);

    private final TaskRepository tasks;
    private final TenantRepository tenants;
    private final TaskService service;
    private final JsonMapper json;
    private final ZoneId zone;

    public TaskScheduler(TaskRepository tasks, TenantRepository tenants, TaskService service, JsonMapper json,
                         @Value("${app.timezone}") String zone) {
        this.tasks = tasks;
        this.tenants = tenants;
        this.service = service;
        this.json = json;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(fixedDelay = "1m", initialDelay = "1m")
    void everyMinute() {
        startDue(Instant.now());
    }

    /** The tasks due at this time - each started once, its next time moved on. */
    void startDue(Instant now) {
        for (Task task : tasks.findDue(now)) {
            try {
                startOne(task, now);
            } catch (RuntimeException e) {
                LOG.error("The scheduled start of task {} of tenant {} failed", task.id(), task.tenantId(), e);
            }
        }
    }

    private void startOne(Task task, Instant now) {
        TaskDefinition definition = definition(task);
        Instant next = TaskSchedule.afterStart(definition.triggers(), task.nextRunAt(), now, zone);
        if (tasks.moveNextRun(task.id(), task.nextRunAt(), next) == 0) {
            return;
        }
        Optional<Tenant> tenant = tenants.findById(task.tenantId());
        if (tenant.isEmpty() || !tenant.get().active() || !tenant.get().tasksAllowed()) {
            return;
        }
        try {
            service.startRun(tenant.get(), task, StartedBy.SCHEDULE, null, null);
        } catch (ServiceException e) {
            LOG.info("Task {} of tenant {} was due but not started: {}", task.id(), task.tenantId(), e.getMessage());
        }
    }

    private TaskDefinition definition(Task task) {
        try {
            return json.readValue(task.definition(), TaskDefinition.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
