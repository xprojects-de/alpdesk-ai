package de.alpdesk.entities.task;

/** Who started a run. */
public enum StartedBy {
    ADMIN,
    TENANT_ADMIN,
    /** Its schedule was due. */
    SCHEDULE,
    /** The client software, with an API key the task is assigned to. */
    API,
    /** The tenant's MCP server, for a task opened to it. */
    MCP,
    /** The tenant's A2A server, with an MCP key the task is assigned to (docs/architecture/a2a.md). */
    A2A
}
