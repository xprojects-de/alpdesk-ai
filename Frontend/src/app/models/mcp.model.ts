/**
 * One of the tenant's MCP keys, as shown - its prefix, never the key. areaIds: the knowledge areas it finds besides the
 * company knowledge; connectionIds: the connected software it may use; taskIds: the scheduled tasks it may start.
 */
export interface McpKey {
    id: number;
    name: string;
    /** What the key releases, in the tenant admin's words - sent to every client of the key; null: none. */
    description: string | null;
    keyPrefix: string;
    webSearch: boolean;
    areaIds: number[];
    connectionIds: number[];
    taskIds: number[];
    createdAt: string;
    lastUsedAt: string | null;
    /** The doors it opens: /mcp (Claude Code, Copilot) and /a2a (foreign agents, docs/architecture/a2a.md). */
    mcp: boolean;
    a2a: boolean;
}

/** What a new key gets, or what a change sets - left out: unchanged. */
export interface McpKeyRequest {
    name?: string;
    /** Empty removes it. */
    description?: string;
    webSearch?: boolean;
    areaIds?: number[];
    connectionIds?: number[];
    taskIds?: number[];
}

/** A new MCP key - the only time the plain key is shown. */
export interface McpKeyCreated {
    key: McpKey;
    plainKey: string;
}

/** What a user may do with a connected software: read (its reading tools) or read and change (all tools). */
export type McpAccessLevel = "READ" | "WRITE";

/** The translation keys of the labels - the template translates them. */
export const MCP_ACCESS_LABELS: Record<McpAccessLevel, string> = {
    READ: "mcpConnections.level.read",
    WRITE: "mcpConnections.level.write",
};

/** A tool of a connected software. changes: not marked read-only by the software (a tenant admin may correct it). */
export interface McpTool {
    id: number;
    name: string;
    description: string;
    changes: boolean;
    enabled: boolean;
    /** False: the software no longer offers it - kept with the admin's choices in case it comes back. */
    offered: boolean;
}

/** An argument of a prompt - the user fills it in a form before the prompt's text goes into the chat's input. */
export interface McpPromptArgument {
    name: string;
    description: string;
    required: boolean;
}

/** A prompt of a connected software, off until a tenant admin turns it on. */
export interface McpPrompt {
    id: number;
    name: string;
    title: string | null;
    description: string;
    arguments: McpPromptArgument[];
    enabled: boolean;
    /** False: the software no longer offers it - kept with the admin's choice in case it comes back. */
    offered: boolean;
}

/** A prompt a user may use in the chat: of which software, and its arguments. */
export interface SoftwarePrompt {
    id: number;
    software: string;
    name: string;
    title: string | null;
    description: string;
    arguments: McpPromptArgument[];
}

/** The tenant's software, connected through its MCP server - its key never comes back, only its hint. */
export interface McpConnection {
    id: number;
    name: string;
    baseUrl: string;
    keyHint: string | null;
    toolsLoadedAt: string | null;
    /** Why reading the tools failed just now (after creating or reloading); null otherwise. */
    toolsError: string | null;
    /** Why reading the prompts failed just now - the tools were read all the same; null otherwise. */
    promptsError: string | null;
    /**
     * MCP keys with it get its reading tools, prompts and instructions directly ("Direkt an MCP-Clients"); false:
     * through the assistant's answers only. Which keys may use it is the keys' choice.
     */
    passesThrough: boolean;
    /** The instructions its server gave, cut to 2,000 characters; null: none. */
    instructions: string | null;
    /** MCP or A2A - an A2A agent's tools are its card's skills, it has no prompts. */
    protocol: McpConnectionProtocol;
    /** The A2A agent answers every question of the tenant's chat - at most one per tenant. */
    answersChat: boolean;
    tools: McpTool[];
    prompts: McpPrompt[];
    /** The sign-in at the software's server when it signs in with OAuth; null: it asks for none (or a key). */
    signIn: McpSignIn | null;
}

/** Where a connection's OAuth sign-in stands. */
export type McpSignInStatus = "AVAILABLE" | "PENDING" | "SIGNED_IN" | "SIGN_IN_NEEDED";

/** A connection's OAuth sign-in as the backend shows it - never a token or a secret. */
export interface McpSignIn {
    status: McpSignInStatus;
    scopesGranted: string[];
    scopesOffered: string[];
    expiresAt: string | null;
    /** Why the last sign-in or renewal failed; null: it did not. */
    lastError: string | null;
    /** The sign-in server's host, to recognise it. */
    issuer: string;
    /** METADATA_DOCUMENT, REGISTERED, ENTERED or null - how the installation is known to the server. */
    clientIdSource: string | null;
    /** The installation's callback address - registered by hand at the server's portal when it takes no registration. */
    redirectUri: string | null;
    /** The server registers no client: its client id is entered in the connection. */
    clientIdNeeded: boolean;
}

/** How a connected software is spoken to - fixed when the connection is made. */
export type McpConnectionProtocol = "MCP" | "A2A";

/** apiKey left out: unchanged (another host than before drops it). */
export interface McpConnectionRequest {
    name: string;
    baseUrl: string;
    apiKey?: string;
    removeApiKey?: boolean;
    protocol: McpConnectionProtocol;
    answersChat?: boolean;
    /** A client id from the server's portal (with its secret), when the server registers no client itself. */
    clientId?: string;
    clientSecret?: string;
}

/** The check of an A2A agent: its card read and timed. */
export interface McpConnectionCheck {
    ok: boolean;
    name: string | null;
    skills: number;
    millis: number;
    error: string | null;
}

/** A user group of the tenant and its members' level for one connection; null: no access. */
export interface McpAccess {
    groupId: number;
    name: string;
    /** The IDM group the WAM proxy must list for a member, if the group names one. */
    wamGroup: string | null;
    level: McpAccessLevel | null;
}
