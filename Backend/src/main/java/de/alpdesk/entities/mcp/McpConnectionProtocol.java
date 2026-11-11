package de.alpdesk.entities.mcp;

/** How a connected software is spoken to (docs/architecture/a2a.md). Fixed when the connection is made. */
public enum McpConnectionProtocol {
    /** A software's MCP server: its tools and prompts. */
    MCP,
    /** An A2A agent: its card's skills, asked through one tool of the chat (docs/architecture/a2a.md). */
    A2A
}
