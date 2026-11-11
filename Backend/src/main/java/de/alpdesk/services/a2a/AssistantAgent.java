package de.alpdesk.services.a2a;

import de.alpdesk.entities.assistant.ChatTemplate;
import de.alpdesk.entities.task.Task;
import de.alpdesk.entities.task.TaskRun;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.chat.ChatService;
import de.alpdesk.services.chat.PreparedChat;
import de.alpdesk.services.chat.SentFile;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.task.TaskService;
import de.alpdesk.services.tools.ToolContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.ContentTypeNotSupportedError;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The tenant's assistant as an A2A agent (docs/architecture/a2a.md "Point 52"): a question asked with an MCP key that
 * opens /a2a is answered like ask_assistant - the key's areas, software and web search. A blocking SendMessage answers
 * one Message and stores nothing; a streamed question (or returnImmediately) is a Task with the answer appended to its
 * artifact "answer" and its files as artifacts of their own, stored for the key. metadata.task of our extension starts a
 * scheduled task assigned to the key: a Task mirroring the run, its report and findings as artifacts at its end.
 */
@Singleton
public class AssistantAgent implements AgentExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantAgent.class);
    private static final Duration RUN_POLL = Duration.ofSeconds(2);
    /**
     * A streamed answer is appended to its artifact in pieces of at least this many characters: the SDK stores the whole
     * task again with every piece, so a piece per word would write the growing answer hundreds of times.
     */
    static final int ANSWER_PIECE = 500;

    private final ChatService chat;
    private final AssistantService assistants;
    private final TaskService tasks;
    private final RunTasks runTasks;
    private final AgentAnswers agentAnswers;
    private final Duration answerTimeout;
    private final Duration agentTimeout;
    private final Duration runWait;
    /** The tasks cancelled while their answer or run goes on - nothing more is emitted for them. */
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    /** The streamed questions the tenant's agent answers - a cancel stops its call, as a stop in the chat does. */
    private final Map<String, PreparedChat> agentAnswering = new ConcurrentHashMap<>();

    public AssistantAgent(ChatService chat, AssistantService assistants, TaskService tasks, RunTasks runTasks,
                          AgentAnswers agentAnswers, @Value("${app.ai.timeout}") Duration answerTimeout,
                          @Value("${app.a2a.timeout}") Duration agentTimeout,
                          @Value("${app.task.a2a-subscribe:30m}") Duration runWait) {
        this.chat = chat;
        this.assistants = assistants;
        this.tasks = tasks;
        this.runTasks = runTasks;
        this.agentAnswers = agentAnswers;
        this.answerTimeout = answerTimeout;
        this.agentTimeout = agentTimeout;
        this.runWait = runWait;
    }

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        ServerCallContext call = context.getCallContext();
        Long tenantId = (Long) call.getState().get(A2aAgentCore.TENANT_ID);
        Long keyId = (Long) call.getState().get(A2aAgentCore.MCP_KEY_ID);
        if (context.getTask() != null) {
            throw new UnsupportedOperationError(null, "A task of this agent takes no further messages", null);
        }
        Message message = context.getMessage();
        Map<?, ?> ours = message.metadata() != null && message.metadata().get(A2aCards.ASSISTANT_EXTENSION) instanceof Map<?, ?> map
                ? map : Map.of();
        agentAnswers.warnIfLoop(tenantId, "/a2a");
        if (ours.get("task") instanceof String taskName && !taskName.isBlank()) {
            runTask(tenantId, keyId, taskName.strip(), emitter);
            return;
        }
        String question = question(message);
        List<SentFile> files = files(message);
        Long templateId = templateId(tenantId, ours.get("template") instanceof String name ? name : null);
        boolean streamed = A2aAgentCore.STREAMING.equals(call.getState().get(A2aAgentCore.METHOD))
                || context.getConfiguration() != null && Boolean.TRUE.equals(context.getConfiguration().returnImmediately());
        if (!streamed) {
            answerOnce(tenantId, keyId, templateId, question, files, emitter);
            return;
        }
        answerAsTask(tenantId, keyId, templateId, question, files, emitter);
    }

    @Override
    public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        String taskId = context.getTaskId();
        cancelled.add(taskId);
        PreparedChat answering = agentAnswering.remove(taskId);
        if (answering != null) {
            answering.stop();
        }
        org.a2aproject.sdk.spec.Task task = context.getTask();
        Long runId = task == null ? null : JdbcTaskStore.runId(task);
        Long scheduledTaskId = task == null ? null : JdbcTaskStore.scheduledTaskId(task);
        if (runId != null && scheduledTaskId != null) {
            Long tenantId = (Long) context.getCallContext().getState().get(A2aAgentCore.TENANT_ID);
            try {
                tasks.cancel(tenantId, de.alpdesk.entities.task.StartedBy.A2A, scheduledTaskId, runId);
            } catch (ServiceException e) {
                LOG.debug("Run {} could not be cancelled: {}", runId, e.code());
            }
        }
        emitter.cancel();
    }

    /** A blocking question: one Message with the answer, its sources and its files - nothing stored. */
    private void answerOnce(Long tenantId, Long keyId, @Nullable Long templateId, String question,
                            List<SentFile> files, AgentEmitter emitter) {
        ChatService.Answer answer;
        try {
            answer = chat.answerOnce(tenantId, ToolContext.Asker.MCP_SERVER, keyId, null, templateId, question, files,
                    null, null);
        } catch (ServiceException e) {
            throw refusal(e);
        }
        List<Part<?>> parts = new ArrayList<>();
        parts.add(new TextPart(withSources(answer)));
        parts.addAll(fileParts(answer.files()));
        emitter.sendMessage(emitter.messageBuilder().parts(parts)
                .extensions(templateId == null ? null : List.of(A2aCards.ASSISTANT_EXTENSION)).build());
    }

    /**
     * A streamed question: the Task first, then the answer appended to its artifact "answer" as it comes - the sources
     * on the last piece -, the files as artifacts, then completed; a failure fails the task with our message.
     */
    private void answerAsTask(Long tenantId, Long keyId, @Nullable Long templateId, String question,
                              List<SentFile> files, AgentEmitter emitter) {
        PreparedChat prepared;
        try {
            prepared = chat.prepareOnce(tenantId, ToolContext.Asker.MCP_SERVER, keyId, null, templateId, question,
                    files, null, null);
        } catch (ServiceException e) {
            throw refusal(e);
        }
        String taskId = emitter.getTaskId();
        if (prepared.tools() != null) {
            // A cancel or the timeout ends the model's answer before its next round, not only its output.
            prepared.tools().stopWhen(() -> cancelled.contains(taskId));
        } else {
            agentAnswering.put(taskId, prepared);
        }
        emitter.addTask(emitter.taskBuilder().status(new TaskStatus(TaskState.TASK_STATE_SUBMITTED)).build());
        emitter.startWork();
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder pending = new StringBuilder();
        boolean[] sent = {false};
        chat.streamOnce(prepared,
                part -> {
                    if (cancelled.contains(taskId)) {
                        return;
                    }
                    pending.append(part);
                    if (pending.length() >= ANSWER_PIECE) {
                        emitter.addArtifact(List.of(new TextPart(pending.toString())), "answer", "answer", null,
                                sent[0], false);
                        sent[0] = true;
                        pending.setLength(0);
                    }
                },
                thinking -> {
                },
                sources -> {
                },
                tool -> {
                },
                answer -> {
                    if (!cancelled.remove(taskId)) {
                        String sources = answer.sources().isEmpty() ? "" : "\n\nSources: " + String.join(", ", answer.sources());
                        String rest = sent[0] ? pending.toString() : answer.text();
                        emitter.addArtifact(List.of(new TextPart(rest + sources)), "answer", "answer", null,
                                sent[0], true);
                        for (Part<?> file : fileParts(answer.files())) {
                            emitter.addArtifact(List.of(file), null, file instanceof FilePart filePart
                                    ? filePart.file().name() : "file", null);
                        }
                        emitter.complete();
                    }
                    done.countDown();
                },
                failure -> {
                    if (!cancelled.remove(taskId)) {
                        emitter.fail(RunTasks.agentMessage(failure.getMessage()));
                    }
                    done.countDown();
                });
        try {
            if (!done.await(waitFor(prepared).toMillis(), TimeUnit.MILLISECONDS)) {
                // Marked like a cancel: the answer still coming is dropped by its callback, which takes the mark.
                cancelled.add(taskId);
                prepared.stop();
                emitter.fail(RunTasks.agentMessage("The answer took too long"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            agentAnswering.remove(taskId);
        }
    }

    /**
     * How long a streamed question's answer is waited for: our model's twice app.ai.timeout; the tenant's agent's as
     * long as the chat waits for it (app.a2a.timeout) and a little more, so its own "did not answer" arrives first.
     */
    private Duration waitFor(PreparedChat prepared) {
        return prepared.agent() != null ? agentTimeout.plusSeconds(30) : answerTimeout.multipliedBy(2);
    }

    /**
     * Starts the scheduled task of that name assigned to the key and mirrors its run - every {@link #RUN_POLL} - until it
     * ends or app.task.a2a-subscribe passed (the store reads the run itself then).
     */
    private void runTask(Long tenantId, Long keyId, String name, AgentEmitter emitter) {
        Task task = tasks.mcpTask(tenantId, keyId, name).orElseThrow(() -> new InvalidParamsError(null,
                "task.notAssigned: no task \"" + name + "\" is assigned to the key", null));
        TaskRun run;
        try {
            run = tasks.startByA2a(tenantId, task);
        } catch (ServiceException e) {
            throw new InvalidParamsError(null, e.code() + ": " + e.getMessage(), null);
        }
        String taskId = emitter.getTaskId();
        emitter.addTask(emitter.taskBuilder().status(RunTasks.status(task, run))
                .metadata(Map.of(A2aCards.ASSISTANT_EXTENSION, Map.of("taskId", task.id(), "run", run.id(),
                        "task", task.name())))
                .build());
        long until = System.nanoTime() + runWait.toNanos();
        boolean working = false;
        try {
            while (System.nanoTime() < until && !cancelled.contains(taskId)) {
                TaskRun current = tasks.findRun(tenantId, task.id(), run.id()).orElse(run);
                if (!current.state().open()) {
                    for (Artifact artifact : runTasks.artifacts(tenantId, task, current)) {
                        emitter.addArtifact(artifact.parts(), artifact.artifactId(), artifact.name(), null);
                    }
                    end(emitter, task, current);
                    return;
                }
                if (!working && current.state() == de.alpdesk.entities.task.RunState.RUNNING) {
                    emitter.startWork(RunTasks.agentMessage(RunTasks.statusText(task, current)));
                    working = true;
                }
                Thread.sleep(RUN_POLL.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cancelled.remove(taskId);
        }
    }

    private static void end(AgentEmitter emitter, Task task, TaskRun run) {
        Message message = RunTasks.agentMessage(RunTasks.statusText(task, run));
        switch (run.state()) {
            case FAILED -> emitter.fail(message);
            case CANCELLED -> emitter.cancel(message);
            default -> emitter.complete(message);
        }
    }

    /** Every text part, joined by a blank line - InvalidParams without one. */
    private static String question(Message message) {
        StringBuilder text = new StringBuilder();
        for (Part<?> part : message.parts()) {
            if (part instanceof TextPart textPart && !textPart.text().isBlank()) {
                text.append(text.isEmpty() ? "" : "\n\n").append(textPart.text());
            }
        }
        if (text.isEmpty()) {
            throw new InvalidParamsError("The message has no text - the question is its text");
        }
        return text.toString();
    }

    /** The files sent with the question: bytes of text only - a link is not fetched, another type not read. */
    private static List<SentFile> files(Message message) {
        List<SentFile> files = new ArrayList<>();
        for (Part<?> part : message.parts()) {
            if (!(part instanceof FilePart file)) {
                continue;
            }
            if (file.file() instanceof FileWithUri) {
                throw new UnsupportedOperationError(null, "A file is taken as bytes only - links are not fetched", null);
            }
            FileWithBytes bytes = (FileWithBytes) file.file();
            String text = text(bytes);
            if (text == null) {
                throw new ContentTypeNotSupportedError(null, "Only text files are read - "
                        + (bytes.mimeType() == null ? "this file" : bytes.mimeType()) + " is not", null);
            }
            files.add(new SentFile(bytes.name(), text));
        }
        return files;
    }

    /** The file's text when it is one: a text type (or none) and valid UTF-8. */
    private static @Nullable String text(FileWithBytes file) {
        String type = file.mimeType() == null ? "" : file.mimeType().toLowerCase();
        boolean textual = type.isBlank() || type.startsWith("text/") || type.contains("json") || type.contains("xml")
                || type.contains("yaml") || type.contains("csv");
        if (!textual) {
            return null;
        }
        try {
            byte[] content = java.util.Base64.getDecoder().decode(file.bytes());
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            return null;
        }
    }

    private @Nullable Long templateId(Long tenantId, @Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return assistants.templates(tenantId).stream()
                .filter(template -> template.name().equalsIgnoreCase(name.strip()))
                .map(ChatTemplate::id)
                .findFirst()
                .orElseThrow(() -> new InvalidParamsError(null, "Unknown template \"" + name + "\"", null));
    }

    private static String withSources(ChatService.Answer answer) {
        return answer.text() + (answer.sources().isEmpty() ? "" : "\n\nSources: " + String.join(", ", answer.sources()));
    }

    /** The answer's files with bytes - a file beyond the 8 MB of an answer named in the text instead, as ask_assistant. */
    private static List<Part<?>> fileParts(List<ChatService.DeliveredFile> files) {
        List<Part<?>> parts = new ArrayList<>();
        for (ChatService.DeliveredFile file : files) {
            parts.add(file.content() == null
                    ? new TextPart("[file \"" + file.name() + "\" left out - the files of one answer may have at most "
                    + "8 MB together]")
                    : new FilePart(new FileWithBytes(file.contentType(), file.name(), file.content())));
        }
        return parts;
    }

    /**
     * A refusal before the answer starts: the request's fault InvalidParams, ours an internal error - our message. The
     * SDK logs an internal error's message: the failure of the tenant's answering agent, whose words may quote the
     * question, goes as data only - the message names its code (privacy.md: the log keeps no prompt).
     */
    private static A2AError refusal(ServiceException e) {
        if (e.status() == HttpStatus.BAD_REQUEST || e.status() == HttpStatus.NOT_FOUND) {
            return new InvalidParamsError(null, e.getMessage(), Map.of("code", e.code()));
        }
        if (e.code().equals("a2a.agentFailed")) {
            return new InternalError(null, "The answer failed (" + e.code() + ")",
                    Map.of("code", e.code(), "error", e.getMessage()));
        }
        return new InternalError(null, e.getMessage(), Map.of("code", e.code()));
    }
}
