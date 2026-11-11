/**
 * The dialog that creates or changes a key - an API key of the client software or an MCP key: what it shows, what it
 * sends, and the short summary of a key's rights in the table.
 */

/** Which choices the dialog shows - only what the tenant has (areas, software, tasks allowed, the web search). */
export interface KeyChoices {
    areas: boolean;
    software: boolean;
    tasks: boolean;
    webSearch: boolean;
    /** Only an API key: whether its questions are kept as conversations. */
    keepsHistory: boolean;
    /** Only an MCP key: what it releases, sent to every client of the key (at most 500 characters). */
    description: boolean;
    /** Only an MCP key: the doors it opens - /mcp and /a2a. */
    doors: boolean;
}

/** The longest description of an MCP key - the backend's limit. */
export const MAX_KEY_DESCRIPTION = 500;

/** The dialog's form as entered. */
export interface KeyFormValue {
    name: string;
    areaIds: number[];
    connectionIds: number[];
    taskIds: number[];
    webSearch: boolean;
    keepsHistory: boolean;
    description: string;
    mcp: boolean;
    a2a: boolean;
}

/** What the dialog sends - a choice it does not show is left out: none on create, unchanged on a change. */
export interface KeyRequest {
    name: string;
    areaIds?: number[];
    connectionIds?: number[];
    taskIds?: number[];
    webSearch?: boolean;
    keepsHistory?: boolean;
    /** Trimmed; empty removes it. */
    description?: string;
    mcp?: boolean;
    a2a?: boolean;
}

/**
 * The request of the dialog's form. A choice the dialog does not show is not sent: a key of a tenant whose tasks the
 * admin switched off keeps its tasks for when they are switched on again.
 */
export function keyRequest(value: KeyFormValue, choices: KeyChoices): KeyRequest {
    return {
        name: value.name.trim(),
        ...(choices.areas ? {areaIds: value.areaIds} : {}),
        ...(choices.software ? {connectionIds: value.connectionIds} : {}),
        ...(choices.tasks ? {taskIds: value.taskIds} : {}),
        ...(choices.webSearch ? {webSearch: value.webSearch} : {}),
        ...(choices.keepsHistory ? {keepsHistory: value.keepsHistory} : {}),
        ...(choices.description ? {description: value.description.trim()} : {}),
        ...(choices.doors ? {mcp: value.mcp, a2a: value.a2a} : {}),
    };
}

/** A key must open a door - /mcp, /a2a or both: the dialog refuses to save with both off, as the backend does. */
export function noDoor(value: Pick<KeyFormValue, "mcp" | "a2a">, choices: KeyChoices): boolean {
    return choices.doors && !value.mcp && !value.a2a;
}

/** A key's rights as the table shows them. */
export interface KeyRights {
    areaIds: number[];
    connectionIds: number[];
    taskIds: number[];
    webSearch: boolean;
}

/** The names of what a key may use, in the order of the tenant's lists - ids no longer known are left out. */
export interface KeySummary {
    areas: string[];
    software: string[];
    tasks: string[];
    webSearch: boolean;
}

export function keySummary(
    key: KeyRights,
    known: {areas: Named[]; software: Named[]; tasks: Named[]},
    choices: KeyChoices,
): KeySummary {
    return {
        areas: choices.areas ? namesOf(key.areaIds, known.areas) : [],
        software: choices.software ? namesOf(key.connectionIds, known.software) : [],
        tasks: choices.tasks ? namesOf(key.taskIds, known.tasks) : [],
        webSearch: choices.webSearch && key.webSearch,
    };
}

export interface Named {
    id: number;
    name: string;
}

function namesOf(ids: number[], known: Named[]): string[] {
    return known.filter(item => ids.includes(item.id)).map(item => item.name);
}
