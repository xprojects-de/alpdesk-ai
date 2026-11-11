package de.alpdesk.entities.mcp;

/** What a user may do with a connected software in the chat; no level: nothing. */
public enum McpAccessLevel {
    /** Its reading tools that are turned on. */
    READ,
    /** All its tools that are turned on - the changing ones asked before every call. */
    WRITE
}
