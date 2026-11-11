import {McpConnectionProtocol, McpConnectionRequest, McpSignIn, McpTool} from "../models/mcp.model";

export interface McpConnectionFormValue {
    /** A client id from the server's portal - only where the server registers no client (the way by hand). */
    clientId: string;
    clientSecret: string;
    name: string;
    baseUrl: string;
    apiKey: string;
    /** Whether the key's field is shown - a signed-in connection shows it only when asked for; hidden, it is not sent. */
    keyShown: boolean;
    removeApiKey: boolean;
    /** Fixed after the connection is made - the dialog sends what it shows. */
    protocol: McpConnectionProtocol;
    /** Only an A2A agent answers the chat - sent only for one. */
    answersChat: boolean;
}

/**
 * The request of the connection dialog: an empty key is left out, so the backend keeps the stored one (on the same
 * host); removing it wins over a new one typed at the same time.
 */
export function buildMcpConnectionRequest(value: McpConnectionFormValue): McpConnectionRequest {
    const request: McpConnectionRequest = {
        name: value.name.trim(),
        baseUrl: value.baseUrl.trim(),
        protocol: value.protocol,
    };
    if (value.protocol === "A2A") {
        request.answersChat = value.answersChat;
    }
    if (value.clientId.trim()) {
        request.clientId = value.clientId.trim();
        if (value.clientSecret.trim()) {
            request.clientSecret = value.clientSecret.trim();
        }
    }
    if (value.removeApiKey) {
        request.removeApiKey = true;
    } else if (value.keyShown && value.apiKey.trim()) {
        request.apiKey = value.apiKey.trim();
    }
    return request;
}

/** The page the browser comes back to after the sign-in: this page, absolute, without its query. */
export function signInReturnUrl(location: {origin: string; pathname: string}): string {
    return location.origin + location.pathname;
}

/** The chip of a connection's sign-in: its translation key and its colour (app-chip ok, warn or info). */
export function signInChip(signIn: McpSignIn): {label: string; tone: "ok" | "warn" | "info"} {
    switch (signIn.status) {
        case "SIGNED_IN":
            return {label: "mcpConnections.signIn.signedIn", tone: "ok"};
        case "PENDING":
            return {label: "mcpConnections.signIn.pending", tone: "info"};
        default:
            return {label: "mcpConnections.signIn.needed", tone: "warn"};
    }
}

/**
 * Back from the server's sign-in page, our callback sent the browser here with the connection and the server's answer
 * ("?connection=<id>&signIn=complete&<answer>"): the connection and the answer, which this page - signed in as the one
 * who started the sign-in - completes it with; null when the address carries none.
 */
export function signInCallback(search: string): {connectionId: number; callback: string} | null {
    const marker = "&signIn=complete&";
    const at = search.indexOf(marker);
    const connection = /^\?connection=(\d+)$/.exec(search.substring(0, Math.max(at, 0)));
    if (at < 0 || !connection) {
        return null;
    }
    return {connectionId: Number(connection[1]), callback: search.substring(at + marker.length)};
}

/**
 * Whether the browser may be sent there: a web address only. The address comes from the connected software - a
 * javascript: one would run in this page.
 */
export function isWebAddress(url: string): boolean {
    return /^https?:\/\//i.test(url);
}

/** Leaves the app for the server's sign-in page - in this tab. */
export function leaveFor(url: string): void {
    window.location.assign(url);
}

/** What a tool does for the users in the chat right now - a translation key. */
export function toolState(tool: McpTool): string {
    if (!tool.offered) {
        return "mcpConnections.toolState.gone";
    }
    if (!tool.enabled) {
        return "mcpConnections.toolState.off";
    }
    return tool.changes ? "mcpConnections.toolState.onWithConfirmation" : "mcpConnections.toolState.on";
}
