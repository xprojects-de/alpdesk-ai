package de.alpdesk.services.tools;

import de.alpdesk.services.sandbox.SandboxSession;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

/**
 * Runs a shell command in the sandbox (docs/architecture/code-execution.md), in the same session and directory as
 * run_python - grep, awk, sort, uniq, jq: what people read large files with.
 */
@Singleton
@Order(9)
public class RunShellTool implements ChatTool {

    public static final String NAME = "run_shell";

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Runs a bash command in the directory whose data/ holds the question's files: grep, awk, sed, "
                    + "sort, uniq, wc, jq, sqlite3, find and the usual tools - where this company's code execution "
                    + "examines videos also ffmpeg and ffprobe (frames, clips); no network. Shares the files with "
                    + "run_python. The output (with stderr) is cut after " + CodeSessions.MAX_OUTPUT + " characters.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("command", "The bash command")
                    .required("command")
                    .build())
            .build();

    private final CodeSessions sessions;
    private final JsonMapper json;

    RunShellTool(CodeSessions sessions, JsonMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    @Override
    public boolean availableFor(ToolContext context) {
        return sessions.available(context);
    }

    /** Waits for the sandbox - up to its timeout; no transaction is held. */
    @Override
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        String command = ToolArguments.of(json, request).requiredString("command");
        SandboxSession session;
        try {
            session = sessions.session(context);
        } catch (CodeSessions.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
        SandboxSession.ShellResult result = session.shell(command);
        if (result.exitCode() == null) {
            return ToolResult.error(result.output());
        }
        return ToolResult.text(CodeSessions.cut("Exit code " + result.exitCode() + "\n" + result.output()));
    }
}
