package de.alpdesk.services.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * A tool the chat's model may call (docs/architecture/ai-features.md "Tools"). Every bean of this type is offered by
 * the {@link Toolbox} - where it can be used for the conversation at hand. A new tool (an API, an MCP server, ...) is
 * one more implementation.
 */
public interface ChatTool {

    /** Name, description and parameters, as the model sees them. */
    ToolSpecification specification();

    /** Whether the tool makes sense for this question - e.g. a search needs documents, reading needs files. */
    default boolean availableFor(ToolContext context) {
        return true;
    }

    /**
     * Runs the call. A mistake in the call (unknown file, missing argument) is answered as text for the model, so it
     * can correct itself - it is not the user's error. Images for the model go into the result's attachments.
     */
    ToolResult execute(ToolContext context, ToolExecutionRequest request);
}
