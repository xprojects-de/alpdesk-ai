package de.alpdesk.services.task;

import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.services.ai.Spent;
import de.alpdesk.services.task.TaskDefinition.Step;

import javax.sql.DataSource;
import java.util.Map;

/**
 * What a step gets when it runs: its run, the tenant's database, and the results of the steps before it by id.
 *
 * @param round 1 the first time, more when an edge back runs it again
 */
public record StepContext(Tenant tenant, DataSource dataSource, Long taskId, Long runId, TaskDefinition task, Step step,
                          int round, Map<String, Map<String, Object>> results, Spent spent) {
}
