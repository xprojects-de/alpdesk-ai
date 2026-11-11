package de.alpdesk.services.tools;

import de.alpdesk.services.sandbox.SandboxSession;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micronaut.core.annotation.Order;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

/**
 * Runs Python in the sandbox (docs/architecture/code-execution.md) like a notebook: one session per answer keeps its
 * variables from call to call, the value of a last expression is shown. Measured on 10 October 2026: models expect
 * that - a fresh process per call made them load everything again and give up.
 */
@Singleton
@Order(8)
public class RunPythonTool implements ChatTool {

    public static final String NAME = "run_python";

    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name(NAME)
            .description("Runs Python 3 like a notebook cell: the session keeps its variables and imports for this "
                    + "answer, the value of a last expression is shown. pandas, numpy, openpyxl, matplotlib, Pillow "
                    + "(PIL - drawing into images) and the standard library; no network. The question's files are in data/ (open(\"data/<name>\")). For "
                    + "counting, grouping, calculating and comparing - large files above all. The output is cut after "
                    + CodeSessions.MAX_OUTPUT + " characters: print summaries and selected lines, not whole files.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("code", "The Python code")
                    .required("code")
                    .build())
            .build();

    private final CodeSessions sessions;
    private final JsonMapper json;

    RunPythonTool(CodeSessions sessions, JsonMapper json) {
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
        String code = ToolArguments.of(json, request).requiredString("code");
        SandboxSession session;
        try {
            session = sessions.session(context);
        } catch (CodeSessions.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
        SandboxSession.PythonResult result = session.python(code);
        if (result.error() != null) {
            return ToolResult.text(CodeSessions.cut(result.output() + "Error:\n" + result.error()));
        }
        return ToolResult.text(result.output().isEmpty() ? "(no output)" : CodeSessions.cut(result.output()));
    }
}
