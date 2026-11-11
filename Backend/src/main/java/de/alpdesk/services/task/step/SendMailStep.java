package de.alpdesk.services.task.step;

import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.StepOutcome;
import de.alpdesk.services.task.TaskDefinition.SendMail;
import de.alpdesk.services.task.TaskMails;
import de.alpdesk.services.task.TaskResults;
import de.alpdesk.services.task.TaskAnswer;
import de.alpdesk.services.task.TaskDefinition.StepType;
import de.alpdesk.entities.task.RunFile;
import de.alpdesk.entities.task.RunRow;
import de.alpdesk.repositories.task.TaskRunRepository;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * send_mail (docs/architecture/tasks.md "Steps"): one mail with what its input steps found - every check point that is
 * not ok, with its evidence lines; a prompt's answer; an extract step's or a call_tool source's unclear rows with their reasons and how many
 * documents and rows the run had. Its recipients, else the task's notify list. A mail that cannot be sent marks the
 * run; it does not fail it.
 */
@Singleton
public class SendMailStep {

    private final TaskMails mails;
    private final TaskRunRepository runs;

    public SendMailStep(TaskMails mails, TaskRunRepository runs) {
        this.mails = mails;
        this.runs = runs;
    }

    public StepOutcome run(StepContext context, Instant started) {
        SendMail settings = context.step().sendMail();
        List<Map<String, Object>> checks = new ArrayList<>();
        List<TaskAnswer> answers = new ArrayList<>();
        for (String input : context.step().input()) {
            StepType type = context.task().step(input).type();
            if (type == StepType.PROMPT) {
                answers.addAll(answers(context, input));
            } else if (type == StepType.EXTRACT || type == StepType.CALL_TOOL) {
                answers.addAll(rows(context, input));
            } else if (type == StepType.WRITE_FILE) {
                answers.addAll(written(context, input));
            } else {
                checks.addAll(TaskFindings.checks(context.results().get(input)));
            }
        }
        List<String> recipients = settings.to().isEmpty() ? context.task().notifyTo() : settings.to();
        String subject = settings.subject() != null ? settings.subject() : context.task().name() + ": findings";
        boolean sent = mails.findings(context.tenant(), context.task(), started, recipients, subject, answers, checks);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.SENT, sent ? recipients.size() : 0);
        return new StepOutcome(result, !sent);
    }

    /**
     * An extract step's or a call_tool source's rows as the mail tells them: the unclear ones first, each with its
     * source and why, then how many documents the run read (an extract step's) and how many rows it made - so one mail
     * serves to check and to confirm.
     */
    private List<TaskAnswer> rows(StepContext context, String step) {
        Map<String, Object> result = context.results().get(step);
        if (result == null) {
            return List.of();
        }
        Set<Long> ids = new HashSet<>();
        if (result.get(TaskResults.ROW_IDS) instanceof List<?> rowIds) {
            rowIds.forEach(id -> ids.add(((Number) id).longValue()));
        }
        StringBuilder text = new StringBuilder();
        for (RunRow row : runs.findRows(context.dataSource(), context.runId())) {
            if (ids.contains(row.id()) && row.unclear() != null) {
                text.append("- ").append(row.source()).append(": ").append(row.unclear()).append('\n');
            }
        }
        text.append(text.isEmpty() ? "" : "\n");
        List<String> from = context.task().step(step).input();
        if (!from.isEmpty()) {
            Map<String, Object> documents = context.results().get(from.getFirst());
            Object read = documents == null ? 0 : documents.getOrDefault(TaskResults.FILES, 0);
            text.append(read).append(" document(s) read, ");
        }
        text.append(result.get(TaskResults.ROWS)).append(" row(s), ").append(result.get(TaskResults.UNCLEAR))
                .append(" of them unclear.");
        return List.of(new TaskAnswer(step, 0, null, text.toString()));
    }

    /** What a write_file step wrote: how many rows into which files, and how many were there already. */
    private List<TaskAnswer> written(StepContext context, String step) {
        Map<String, Object> result = context.results().get(step);
        if (result == null) {
            return List.of();
        }
        Object files = result.get(TaskResults.WRITTEN);
        String into = files instanceof List<?> list && !list.isEmpty()
                ? " into " + String.join(", ", list.stream().map(String::valueOf).toList()) : "";
        return List.of(new TaskAnswer(step, 0, null, result.get(TaskResults.ROWS) + " row(s) written" + into + ", "
                + result.get(TaskResults.SKIPPED) + " already there."));
    }

    /** The prompt step's last answer, with its choice. */
    private List<TaskAnswer> answers(StepContext context, String step) {
        Map<String, Object> result = context.results().get(step);
        if (result == null) {
            return List.of();
        }
        List<TaskAnswer> answers = new ArrayList<>();
        for (RunFile file : StepInputs.files(runs, context, List.of(step))) {
            answers.add(new TaskAnswer(step, 0, (String) result.get(TaskResults.CHOICE), StepInputs.text(file)));
        }
        return answers;
    }
}
