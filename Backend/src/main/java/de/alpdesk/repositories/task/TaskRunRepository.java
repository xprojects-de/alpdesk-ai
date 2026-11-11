package de.alpdesk.repositories.task;

import de.alpdesk.entities.task.ReadPosition;
import de.alpdesk.entities.task.RunFile;
import de.alpdesk.entities.task.RunRow;
import de.alpdesk.entities.task.RunState;
import de.alpdesk.entities.task.StartedBy;
import de.alpdesk.entities.task.StepState;
import de.alpdesk.entities.task.TaskRun;
import de.alpdesk.entities.task.TaskRunStep;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The runs of a tenant's tasks in its database, by plain JDBC (see ConversationRepository): task_run with its steps,
 * the lines read (task_run_file), the read positions and langgraph4j's checkpoints. A run is reached through its task,
 * which belongs to one tenant.
 */
@Singleton
public class TaskRunRepository {

    private static final String UNIQUE_VIOLATION = "23505";

    /** The new run, waiting; empty when the task has a run still open - one run of a task at a time. */
    public Optional<TaskRun> create(DataSource dataSource, Long taskId, int version, String definition,
                                    StartedBy startedBy, @Nullable Long userId, @Nullable Long apiKeyId) {
        String sql = "INSERT INTO task_run (task_id, task_version, definition, state, started_by, started_by_user, "
                + "started_by_key, created_at) VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?) RETURNING *";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            statement.setInt(2, version);
            statement.setString(3, definition);
            statement.setString(4, startedBy.name());
            setLong(statement, 5, userId);
            setLong(statement, 6, apiKeyId);
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return Optional.of(map(result));
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return Optional.empty();
            }
            throw new IllegalStateException("Could not store the run", e);
        }
    }

    public Optional<TaskRun> findById(DataSource dataSource, Long taskId, Long runId) {
        return runs(dataSource, "SELECT * FROM task_run WHERE id = ? AND task_id = ?", runId, taskId).stream().findFirst();
    }

    /** Newest first. */
    public List<TaskRun> findByTask(DataSource dataSource, Long taskId, int limit, int offset) {
        return runs(dataSource, "SELECT * FROM task_run WHERE task_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                taskId, (long) limit, (long) offset);
    }

    public Optional<TaskRun> findLast(DataSource dataSource, Long taskId) {
        return findByTask(dataSource, taskId, 1, 0).stream().findFirst();
    }

    /**
     * The runs still waiting or running - after a restart they go on. On a connection of its own: the start reads every
     * tenant, and a pool per tenant would take the server's last connections.
     */
    public List<TaskRun> findOpen(Connection connection) {
        String sql = "SELECT * FROM task_run WHERE state IN ('QUEUED', 'RUNNING') ORDER BY id";
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            List<TaskRun> runs = new ArrayList<>();
            while (result.next()) {
                runs.add(map(result));
            }
            return runs;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the open runs", e);
        }
    }

    /** False when the run is no longer open (ended meanwhile). */
    public boolean markRunning(DataSource dataSource, Long runId) {
        return update(dataSource, "UPDATE task_run SET state = 'RUNNING', started_at = COALESCE(started_at, ?) "
                + "WHERE id = ? AND state IN ('QUEUED', 'RUNNING')", Timestamp.from(Instant.now()), runId) > 0;
    }

    public void finish(DataSource dataSource, Long runId, RunState state, @Nullable String errorCode,
                       @Nullable String errorMessage) {
        update(dataSource, "UPDATE task_run SET state = ?, error_code = ?, error_message = ?, ended_at = ? WHERE id = ?",
                state.name(), errorCode, errorMessage, Timestamp.from(Instant.now()), runId);
    }

    /**
     * Cancels a run: one still waiting ends at once, a running one is marked and ends before its next step or round.
     * False when the run is no longer open.
     */
    public boolean cancel(DataSource dataSource, Long taskId, Long runId) {
        Timestamp now = Timestamp.from(Instant.now());
        if (update(dataSource, "UPDATE task_run SET state = 'CANCELLED', cancel_requested = true, ended_at = ? "
                + "WHERE id = ? AND task_id = ? AND state = 'QUEUED'", now, runId, taskId) > 0) {
            return true;
        }
        return update(dataSource, "UPDATE task_run SET cancel_requested = true WHERE id = ? AND task_id = ? "
                + "AND state = 'RUNNING'", runId, taskId) > 0;
    }

    /** Ends a running run as cancelled at once - for one no thread carries out any more. False when it is not running. */
    public boolean endCancelled(DataSource dataSource, Long taskId, Long runId) {
        return update(dataSource, "UPDATE task_run SET state = 'CANCELLED', ended_at = ? WHERE id = ? AND task_id = ? "
                + "AND state = 'RUNNING'", Timestamp.from(Instant.now()), runId, taskId) > 0;
    }

    public boolean cancelRequested(DataSource dataSource, Long runId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT cancel_requested FROM task_run WHERE id = ?")) {
            statement.setLong(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the run", e);
        }
    }

    public void markMailFailed(DataSource dataSource, Long runId) {
        update(dataSource, "UPDATE task_run SET mail_failed = true WHERE id = ?", runId);
    }

    /** Adds what a step spent to the run's totals - a step run again after a restart adds its calls again, as the usage has them. */
    public void addSpent(DataSource dataSource, Long runId, TaskRunStep step) {
        update(dataSource, "UPDATE task_run SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?, "
                + "cache_read_tokens = cache_read_tokens + ?, cache_write_tokens = cache_write_tokens + ?, "
                + "cost = COALESCE(cost, 0) + ? WHERE id = ?", step.inputTokens(), step.outputTokens(),
                step.cacheReadTokens(), step.cacheWriteTokens(), step.cost(), runId);
    }

    /** False for a run still open - it is not deleted under its own feet. */
    public boolean delete(DataSource dataSource, Long taskId, Long runId) {
        return deleteRuns(dataSource, "id = ? AND task_id = ? AND state NOT IN ('QUEUED', 'RUNNING')", runId, taskId) > 0;
    }

    public void deleteEnded(DataSource dataSource, Long taskId) {
        deleteRuns(dataSource, "task_id = ? AND state NOT IN ('QUEUED', 'RUNNING')", taskId);
    }

    /** Everything of a deleted task: its runs (with their steps and files) and its read positions. */
    public void deleteTask(DataSource dataSource, Long taskId) {
        deleteRuns(dataSource, "task_id = ?", taskId);
        update(dataSource, "DELETE FROM task_read_position WHERE task_id = ?", taskId);
    }

    /**
     * The runs that ended before this moment, with their steps, files and checkpoints - the run retention
     * (TaskRunCleanupJob, on a connection of its own). A run still open is never one of them, nor a task's last run
     * that ended DONE or UNCLEAR: {lastRun} is its start (lastSuccessfulStart), and a task running less often than the
     * retention would otherwise ask its source for everything on every run.
     */
    public int deleteEndedBefore(Connection connection, Instant before) {
        return deleteRuns(connection, "state NOT IN ('QUEUED', 'RUNNING') AND ended_at < ? AND id NOT IN ("
                + "SELECT DISTINCT ON (task_id) id FROM task_run WHERE state IN ('DONE', 'UNCLEAR') "
                + "AND started_at IS NOT NULL ORDER BY task_id, started_at DESC)", Timestamp.from(before));
    }

    /**
     * The runs with their checkpoints, should any be left - the runner deletes them at every end of a run, but a
     * failure there would keep a run's state after the run itself is gone. The thread's name is TaskRunner.thread.
     */
    private static int deleteRuns(DataSource dataSource, String where, Object... parameters) {
        try (Connection connection = dataSource.getConnection()) {
            return deleteRuns(connection, where, parameters);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the runs", e);
        }
    }

    private static int deleteRuns(Connection connection, String where, Object... parameters) {
        String sql = "WITH gone AS (DELETE FROM task_run WHERE " + where + " RETURNING id), "
                + "threads AS (DELETE FROM lg4jthread WHERE thread_name IN (SELECT 'run-' || id FROM gone)) "
                + "SELECT count(*) FROM gone";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete the runs", e);
        }
    }

    /** One row per step and round; a step run again after a restart replaces its row. */
    public void saveStep(DataSource dataSource, Long runId, TaskRunStep step) {
        String sql = "INSERT INTO task_run_step (run_id, step_id, round, state, result, error_code, input_tokens, "
                + "output_tokens, cache_read_tokens, cache_write_tokens, cost, started_at, ended_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (run_id, step_id, round) DO UPDATE SET state = EXCLUDED.state, result = EXCLUDED.result, "
                + "error_code = EXCLUDED.error_code, input_tokens = EXCLUDED.input_tokens, "
                + "output_tokens = EXCLUDED.output_tokens, cache_read_tokens = EXCLUDED.cache_read_tokens, "
                + "cache_write_tokens = EXCLUDED.cache_write_tokens, cost = EXCLUDED.cost, "
                + "started_at = EXCLUDED.started_at, ended_at = EXCLUDED.ended_at";
        update(dataSource, sql, runId, step.stepId(), step.round(), step.state().name(), step.result(), step.errorCode(),
                step.inputTokens(), step.outputTokens(), step.cacheReadTokens(), step.cacheWriteTokens(), step.cost(),
                Timestamp.from(step.startedAt()), Timestamp.from(step.endedAt()));
    }

    public List<TaskRunStep> findSteps(DataSource dataSource, Long runId) {
        String sql = "SELECT * FROM task_run_step WHERE run_id = ? ORDER BY started_at, id";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            List<TaskRunStep> steps = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    steps.add(new TaskRunStep(result.getString("step_id"), result.getInt("round"),
                            StepState.valueOf(result.getString("state")), result.getString("result"),
                            result.getString("error_code"), result.getLong("input_tokens"),
                            result.getLong("output_tokens"), result.getLong("cache_read_tokens"),
                            result.getLong("cache_write_tokens"), result.getBigDecimal("cost"),
                            result.getTimestamp("started_at").toInstant(),
                            result.getTimestamp("ended_at").toInstant()));
                }
            }
            return steps;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the run's steps", e);
        }
    }

    /** The tokens of every step and round of these runs - what a page of runs needs for its cost, without the results. */
    public List<StepTokens> findStepTokens(DataSource dataSource, List<Long> runIds) {
        String sql = "SELECT run_id, step_id, input_tokens, output_tokens FROM task_run_step WHERE run_id = ANY(?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("bigint", runIds.toArray()));
            List<StepTokens> tokens = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tokens.add(new StepTokens(result.getLong("run_id"), result.getString("step_id"),
                            result.getLong("input_tokens"), result.getLong("output_tokens")));
                }
            }
            return tokens;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the runs' tokens", e);
        }
    }

    /** One round of a step: its tokens. */
    public record StepTokens(Long runId, String stepId, long inputTokens, long outputTokens) {
    }

    public Long saveFile(DataSource dataSource, Long runId, String stepId, String name, List<RunFile.Line> lines) {
        StringBuilder text = new StringBuilder();
        for (RunFile.Line line : lines) {
            text.append(line.number()).append('\t').append(line.text()).append('\n');
        }
        String sql = "INSERT INTO task_run_file (run_id, step_id, name, lines) VALUES (?, ?, ?, ?) RETURNING id";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setString(2, stepId);
            statement.setString(3, name);
            statement.setString(4, text.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the lines read", e);
        }
    }

    /** The run's files of these ids, in that order. */
    public List<RunFile> findFiles(DataSource dataSource, Long runId, List<Long> ids) {
        List<RunFile> files = new ArrayList<>();
        String sql = "SELECT id, name, lines FROM task_run_file WHERE run_id = ? AND id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Long id : ids) {
                statement.setLong(1, runId);
                statement.setLong(2, id);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        files.add(new RunFile(result.getLong("id"), result.getString("name"), lines(result.getString("lines"))));
                    }
                }
            }
            return files;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the lines read", e);
        }
    }

    /** When the task's last run that ended DONE or UNCLEAR started - another than this one; null: none yet. */
    public @Nullable Instant lastSuccessfulStart(DataSource dataSource, Long taskId, Long runId) {
        String sql = "SELECT started_at FROM task_run WHERE task_id = ? AND id <> ? AND state IN ('DONE', 'UNCLEAR') "
                + "AND started_at IS NOT NULL ORDER BY started_at DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            statement.setLong(2, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getTimestamp(1).toInstant() : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the task's last run", e);
        }
    }

    public Long saveRow(DataSource dataSource, Long runId, String stepId, String source, String values,
                        @Nullable String unclear) {
        String sql = "INSERT INTO task_run_row (run_id, step_id, source, \"values\", unclear) VALUES (?, ?, ?, ?, ?) "
                + "RETURNING id";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setString(2, stepId);
            statement.setString(3, source);
            statement.setString(4, values);
            statement.setString(5, unclear);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store a row", e);
        }
    }

    /** The run's rows, in the order they were made - of every extract step. */
    public List<RunRow> findRows(DataSource dataSource, Long runId) {
        String sql = "SELECT id, step_id, source, \"values\", unclear FROM task_run_row WHERE run_id = ? ORDER BY id";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            List<RunRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new RunRow(result.getLong("id"), result.getString("step_id"), result.getString("source"),
                            result.getString("values"), result.getString("unclear")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the rows", e);
        }
    }

    public List<ReadPosition> findPositions(DataSource dataSource, Long taskId, Long storeId) {
        String sql = "SELECT * FROM task_read_position WHERE task_id = ? AND store_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            statement.setLong(2, storeId);
            List<ReadPosition> positions = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Timestamp modified = result.getTimestamp("modified_at");
                    positions.add(new ReadPosition(result.getLong("store_id"), result.getString("path"),
                            result.getLong("byte_offset"), result.getLong("line_number"),
                            modified == null ? null : modified.toInstant()));
                }
            }
            return positions;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the read positions", e);
        }
    }

    public void savePositions(DataSource dataSource, Long taskId, List<ReadPosition> positions) {
        String sql = "INSERT INTO task_read_position (task_id, store_id, path, byte_offset, line_number, modified_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (task_id, store_id, path) DO UPDATE SET "
                + "byte_offset = EXCLUDED.byte_offset, line_number = EXCLUDED.line_number, modified_at = EXCLUDED.modified_at";
        for (ReadPosition position : positions) {
            update(dataSource, sql, taskId, position.storeId(), position.path(), position.byteOffset(),
                    position.lineNumber(), position.modifiedAt() == null ? null : Timestamp.from(position.modifiedAt()));
        }
    }

    /** langgraph4j's checkpoints of one run - its saver only marks them released. */
    public void deleteCheckpoints(DataSource dataSource, String threadName) {
        update(dataSource, "DELETE FROM lg4jthread WHERE thread_name = ?", threadName);
    }

    private static List<RunFile.Line> lines(String text) {
        List<RunFile.Line> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                lines.add(new RunFile.Line(Long.parseLong(line.substring(0, tab)), line.substring(tab + 1)));
            }
        }
        return lines;
    }

    private List<TaskRun> runs(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            List<TaskRun> runs = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    runs.add(map(result));
                }
            }
            return runs;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the runs", e);
        }
    }

    private static int update(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not write the run", e);
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            Object parameter = parameters[i];
            if (parameter == null) {
                statement.setNull(i + 1, Types.NULL);
            } else {
                statement.setObject(i + 1, parameter);
            }
        }
    }

    private static void setLong(PreparedStatement statement, int index, @Nullable Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static TaskRun map(ResultSet result) throws SQLException {
        long user = result.getLong("started_by_user");
        Long startedByUser = result.wasNull() ? null : user;
        long key = result.getLong("started_by_key");
        Long startedByKey = result.wasNull() ? null : key;
        Timestamp started = result.getTimestamp("started_at");
        Timestamp ended = result.getTimestamp("ended_at");
        return new TaskRun(result.getLong("id"), result.getLong("task_id"), result.getInt("task_version"),
                result.getString("definition"), RunState.valueOf(result.getString("state")),
                StartedBy.valueOf(result.getString("started_by")), startedByUser, startedByKey,
                result.getBoolean("cancel_requested"), result.getString("error_code"),
                result.getString("error_message"), result.getBoolean("mail_failed"), result.getLong("input_tokens"),
                result.getLong("output_tokens"), result.getLong("cache_read_tokens"), result.getLong("cache_write_tokens"),
                result.getBigDecimal("cost"), result.getTimestamp("created_at").toInstant(),
                started == null ? null : started.toInstant(), ended == null ? null : ended.toInstant());
    }
}
