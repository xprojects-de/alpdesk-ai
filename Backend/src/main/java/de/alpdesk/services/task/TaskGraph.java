package de.alpdesk.services.task;

import de.alpdesk.services.task.TaskDefinition.Edge;
import de.alpdesk.services.task.TaskDefinition.Step;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/**
 * A task's definition as a langgraph4j graph, built at the start of a run (docs/architecture/tasks.md "Engine"): a node
 * per step, after each one conditional edge - an edge back while its limit allows, else the next step. A step decides
 * itself whether it is skipped (its condition, its inputs): the edge functions only read the state, since langgraph4j
 * may call one again when a run is resumed. The state is plain maps - the steps' results and how often each ran.
 */
final class TaskGraph {

    static final String RESULTS = "results";
    static final String VISITS = "visits";
    /** The step whose node ran last, when it skipped - the edges after a skipped step are not taken. */
    static final String SKIPPED = "skipped";

    /** What runs a step - the runner records it, the graph only routes. */
    interface Steps {

        /** The step's structured result. */
        Map<String, Object> run(Step step, int round, Map<String, Map<String, Object>> results) throws Exception;

        void skipped(Step step, int round);
    }

    static final class TaskState extends AgentState {
        TaskState(Map<String, Object> data) {
            super(data);
        }
    }

    /** JSON, read back as maps and lists only - never Java serialisation. */
    static final class Serializer extends JacksonStateSerializer<TaskState> {
        Serializer() {
            super(TaskState::new);
        }
    }

    private TaskGraph() {
    }

    static CompiledGraph<TaskState> compile(TaskDefinition task, Steps steps, BaseCheckpointSaver saver)
            throws GraphStateException {
        StateGraph<TaskState> graph = new StateGraph<>(new Serializer());
        Map<String, String> targets = new HashMap<>();
        for (Step step : task.steps()) {
            targets.put(step.id(), step.id());
        }
        targets.put(END, END);
        for (Step step : task.steps()) {
            graph.addNode(step.id(), (state, config) -> node(step, state, steps));
        }
        graph.addEdge(START, task.steps().getFirst().id());
        for (int i = 0; i < task.steps().size(); i++) {
            int index = i;
            graph.addConditionalEdges(task.steps().get(i).id(), edge_async(state -> route(task, index, state)), targets);
        }
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .recursionLimit(task.steps().size() * (TaskCompiler.MAX_ROUNDS + 1) + 10)
                .build());
    }

    private static CompletableFuture<Map<String, Object>> node(Step step, TaskState state, Steps steps) {
        try {
            Map<String, Map<String, Object>> results = results(state);
            Map<String, Integer> visits = new HashMap<>(visits(state));
            int round = visits.getOrDefault(step.id(), 0) + 1;
            visits.put(step.id(), round);
            if (skips(step, results)) {
                steps.skipped(step, round);
                return CompletableFuture.completedFuture(Map.of(VISITS, visits, SKIPPED, step.id()));
            }
            Map<String, Map<String, Object>> updated = new LinkedHashMap<>(results);
            updated.put(step.id(), steps.run(step, round, results));
            return CompletableFuture.completedFuture(Map.of(RESULTS, updated, VISITS, visits, SKIPPED, ""));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Its condition is false - or it takes input, and none of its inputs gave a result (skipped, jumped over). */
    private static boolean skips(Step step, Map<String, Map<String, Object>> results) {
        if (step.when() != null && !step.when().holds(results)) {
            return true;
        }
        return !step.input().isEmpty() && step.input().stream().noneMatch(results::containsKey);
    }

    /** The first edge whose condition holds - one back only while its limit allows; none: the next step, or the end. */
    private static String route(TaskDefinition task, int index, TaskState state) {
        Step step = task.steps().get(index);
        Map<String, Map<String, Object>> results = results(state);
        if (!step.id().equals(state.data().get(SKIPPED))) {
            for (Edge edge : step.next()) {
                if (edge.when() != null && !edge.when().holds(results)) {
                    continue;
                }
                if (isBack(task, index, edge) && !backAllowed(edge, state)) {
                    continue;
                }
                return edge.to();
            }
        }
        return index + 1 < task.steps().size() ? task.steps().get(index + 1).id() : END;
    }

    /**
     * Whether a run ended with an edge back that wanted to go on but had reached its limit - a finding stayed unclear,
     * and the run must never pass for "nothing found".
     */
    static boolean limitReached(TaskDefinition task, Map<String, Object> data) {
        TaskState state = new TaskState(data);
        Map<String, Map<String, Object>> results = results(state);
        for (int i = 0; i < task.steps().size(); i++) {
            Step step = task.steps().get(i);
            for (Edge edge : step.next()) {
                boolean wanted = results.containsKey(step.id()) && (edge.when() == null || edge.when().holds(results));
                if (wanted && isBack(task, i, edge) && !backAllowed(edge, state)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBack(TaskDefinition task, int index, Edge edge) {
        return task.indexOf(edge.to()) <= index;
    }

    /** maxRounds is how often it goes back: its target has run once plus once per time back. */
    private static boolean backAllowed(Edge edge, TaskState state) {
        int ran = visits(state).getOrDefault(edge.to(), 0);
        return edge.maxRounds() != null && ran <= edge.maxRounds();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> results(AgentState state) {
        return (Map<String, Map<String, Object>>) state.data().getOrDefault(RESULTS, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> visits(AgentState state) {
        Map<String, Object> raw = (Map<String, Object>) state.data().getOrDefault(VISITS, Map.of());
        Map<String, Integer> visits = new HashMap<>();
        raw.forEach((step, count) -> visits.put(step, ((Number) count).intValue()));
        return visits;
    }
}
