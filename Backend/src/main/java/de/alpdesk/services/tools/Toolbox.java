package de.alpdesk.services.tools;

import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.mcp.McpTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * All {@link ChatTool}s, and the tools of the tenant's connected software the asking user may use ({@link McpTools}):
 * which ones a question gets, and running the model's calls.
 */
@Singleton
public class Toolbox {

    private static final Logger LOG = LoggerFactory.getLogger(Toolbox.class);

    /** In their @Order - the order a model sees them in, whatever order the beans were found in. */
    private final List<ChatTool> tools;
    private final McpTools softwareTools;

    public Toolbox(List<ChatTool> tools, McpTools softwareTools) {
        this.tools = tools;
        this.softwareTools = softwareTools;
    }

    /** The tools that can be used for this question - the connected software's ones are kept in the context. */
    public List<ToolSpecification> specificationsFor(ToolContext context) {
        List<ToolSpecification> specifications = new ArrayList<>();
        for (ChatTool tool : tools) {
            if (context.allows(tool.specification().name()) && tool.availableFor(context)) {
                specifications.add(tool.specification());
            }
        }
        context.softwareTools(softwareToolsFor(context));
        for (McpTools.Offered offered : context.softwareTools()) {
            specifications.add(offered.specification());
        }
        return specifications;
    }

    /** The connected software's changing tool this call names - the user decides before it runs; empty otherwise. */
    public Optional<McpTools.Offered> askingFirst(ToolContext context, ToolExecutionRequest request) {
        return softwareTool(context, request).filter(McpTools.Offered::asksFirst);
    }

    /** Runs a changing call the user confirmed - a refusal or failure at the software is a text for the model. */
    public ToolResult executeConfirmed(ToolContext context, McpTools.Offered offered, ToolExecutionRequest request) {
        return executeSoftwareTool(context, offered, request);
    }

    /**
     * Runs one call. An unknown tool, one not available for this question (or no longer: the sandbox went down during
     * the answer) or a refusal of the tool (ServiceException) becomes an error text for the model; anything else is a
     * fault of ours and fails the answer.
     */
    public ToolResult execute(ToolContext context, ToolExecutionRequest request) {
        Optional<McpTools.Offered> software = softwareTool(context, request);
        if (software.isPresent()) {
            return executeSoftwareTool(context, software.get(), request);
        }
        Optional<ChatTool> tool = tools.stream()
                .filter(candidate -> candidate.specification().name().equals(request.name()))
                .filter(candidate -> context.allows(request.name()))
                .findFirst();
        if (tool.isEmpty()) {
            return ToolResult.error("there is no tool " + request.name());
        }
        if (!tool.get().availableFor(context)) {
            return ToolResult.error("the tool " + request.name() + " is not available right now - go on without it");
        }
        // The arguments may carry the company's data (a search, code, a file) - only the tool's name is logged.
        LOG.debug("Tool {} for tenant {}", request.name(), context.tenant().id());
        try {
            return tool.get().execute(context, request);
        } catch (ServiceException e) {
            LOG.debug("Tool {} refused: {}", request.name(), e.getMessage());
            return ToolResult.error(e.getMessage());
        }
    }

    /** The call as the chat shows it while it runs: a connected software's tool by its own and the software's name. */
    public ToolStep step(ToolContext context, ToolExecutionRequest request) {
        return softwareTool(context, request)
                .map(offered -> new ToolStep(offered.tool().name(), offered.connection().name()))
                .orElseGet(() -> new ToolStep(request.name(), null));
    }

    /**
     * A user's by its levels; a question over the MCP server or from the client software the ones of its key - less
     * the software the question leaves out.
     */
    private List<McpTools.Offered> softwareToolsFor(ToolContext context) {
        return reachedSoftwareTools(context).stream()
                .filter(offered -> !context.excludedConnections().contains(offered.connection().id()))
                .toList();
    }

    private List<McpTools.Offered> reachedSoftwareTools(ToolContext context) {
        return switch (context.asker()) {
            case USER -> softwareTools.forUser(context.tenant().id(), context.userId());
            case MCP_SERVER -> softwareTools.forMcpKey(context.tenant().id(), context.keyId());
            case CLIENT_SOFTWARE -> softwareTools.forApiKey(context.tenant().id(), context.keyId());
            case TASK -> context.taskConnections().isEmpty() ? List.of()
                    : softwareTools.forTask(context.tenant().id(), context.taskConnections());
        };
    }

    private static Optional<McpTools.Offered> softwareTool(ToolContext context, ToolExecutionRequest request) {
        return context.softwareTools().stream()
                .filter(offered -> offered.specification().name().equals(request.name()))
                .findFirst();
    }

    /** A connected software's tool: a refusal or failure there is a text the model reads, like a wrong call here. */
    private ToolResult executeSoftwareTool(ToolContext context, McpTools.Offered offered, ToolExecutionRequest request) {
        // The arguments may carry the company's data - only the tool's name is logged.
        LOG.debug("Tool {} of {} for tenant {}", offered.tool().name(), offered.connection().name(), context.tenant().id());
        try {
            return softwareTools.execute(context, offered, request);
        } catch (ServiceException e) {
            // By its code only: the software's message may quote a row of the company's data.
            LOG.debug("Tool {} of {} failed: {}", offered.tool().name(), offered.connection().name(), e.code());
            return ToolResult.error(e.getMessage());
        }
    }
}
