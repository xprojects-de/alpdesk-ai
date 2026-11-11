package de.alpdesk.services.task;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.repositories.ai.AiModelRepository;
import de.alpdesk.services.ai.UsageService;
import de.alpdesk.services.task.TaskDefinition.Step;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a run from before costs were kept cost (docs/architecture/tasks.md "Data"): the tokens a step kept times the
 * price its model has in the catalog today, without tiers - the model of the run's own definition, the version that
 * ran. A run since keeps its cost as the usage recorded it.
 */
@Singleton
public class RunCosts {

    private final AiModelRepository models;

    public RunCosts(AiModelRepository models) {
        this.models = models;
    }

    /** The catalog's models the steps of these definitions name - read once for a page of runs. */
    public Prices prices(Collection<TaskDefinition> definitions) {
        Set<Long> ids = new HashSet<>();
        for (TaskDefinition definition : definitions) {
            for (Step step : definition.steps()) {
                if (step.agent() != null && step.agent().modelId() != null) {
                    ids.add(step.agent().modelId());
                }
            }
        }
        Map<Long, AiModel> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (AiModel model : models.findByIdIn(ids)) {
                byId.put(model.id(), model);
            }
        }
        return new Prices(byId);
    }

    /** The models of a page of runs, by id. */
    public record Prices(Map<Long, AiModel> models) {

        /** One round of a step; null when it used no tokens, or its model is no longer in the catalog. */
        @Nullable
        public BigDecimal of(TaskDefinition definition, String stepId, long inputTokens, long outputTokens) {
            if (inputTokens == 0 && outputTokens == 0) {
                return null;
            }
            int index = definition.indexOf(stepId);
            Step step = index < 0 ? null : definition.steps().get(index);
            AiModel model = step == null || step.agent() == null ? null : models.get(step.agent().modelId());
            return model == null ? null : UsageService.amountOfSeveralCalls(model, inputTokens, outputTokens);
        }
    }

    /**
     * A run's cost, summed while its steps are read: partial when a step used tokens whose model is gone - the
     * amount is then what is known, at least.
     */
    public static final class Sum {

        private BigDecimal amount = BigDecimal.ZERO;
        private boolean partial;

        public void add(@Nullable BigDecimal cost, long inputTokens, long outputTokens) {
            if (cost != null) {
                amount = amount.add(cost);
            } else if (inputTokens > 0 || outputTokens > 0) {
                partial = true;
            }
        }

        public BigDecimal amount() {
            return amount;
        }

        public boolean partial() {
            return partial;
        }
    }
}
