/**
 * Where the backend answers, absolute: the configured API address (development), or this page's origin with the path
 * the app is served under (production serves both - at the root, or e.g. /alpdesk-ai behind a reverse proxy).
 */
export function backendOrigin(apiUrl: string, pageOrigin: string): string {
    const address = /^https?:\/\//.test(apiUrl) ? apiUrl : pageOrigin.replace(/\/+$/, "") + apiUrl;
    return address.replace(/\/+$/, "");
}

/** The longest server name we offer - Claude Code shows it before every tool (mcp__<name>__ask_assistant). */
const MAX_SERVER_NAME = 40;

/**
 * The name the tenant's MCP server gets in Claude Code and Copilot: the tenant's brand, the way a command takes it
 * ("Muster AI" → "muster-ai", "Müller Technik" → "mueller-technik"). The model sees it before every tool, so a question
 * naming the brand finds the server.
 */
export function mcpServerName(brand: string): string {
    const name = brand
        .toLowerCase()
        .replace(/ä/g, "ae")
        .replace(/ö/g, "oe")
        .replace(/ü/g, "ue")
        .replace(/ß/g, "ss")
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .replace(/[^a-z0-9]+/g, "-")
        .slice(0, MAX_SERVER_NAME)
        .replace(/^-+|-+$/g, "");
    return name || "assistant";
}

/** The tenant's assistant as an A2A agent: its address and its public card (docs/architecture/a2a.md). */
export function a2aAddresses(origin: string): {address: string; card: string} {
    return {address: `${origin}/a2a`, card: `${origin}/.well-known/agent-card.json`};
}

/** Where the key goes in the command shown to the people, who get the key from a tenant admin. */
export const MCP_KEY_PLACEHOLDER = "<MCP-KEY>";

/** The command that connects Claude Code to the tenant's MCP server with its key, named after the brand. */
export function claudeCodeCommand(origin: string, key: string, brand: string): string {
    const name = mcpServerName(brand);
    return `claude mcp add --transport http ${name} ${origin}/mcp --header "Authorization: Bearer ${key}"`;
}

/** What the dialog "Add custom connector" of claude.ai, Claude Desktop and Cowork takes, field by field. */
export interface ClaudeConnector {
    name: string;
    url: string;
    header: string;
    value: string;
}

/**
 * The custom connector for claude.ai, Claude Desktop and Cowork: authentication "No sign-in" and the key as a request
 * header. Claude sends the header's value exactly as typed - without "Bearer " the server answers 401 - so the value is
 * given whole, scheme and space included.
 */
export function claudeConnector(origin: string, key: string, brand: string): ClaudeConnector {
    return {name: brand, url: `${origin}/mcp`, header: "authorization", value: `Bearer ${key}`};
}

/**
 * The VS Code configuration (.vscode/mcp.json) that connects GitHub Copilot to the tenant's MCP server, named after the
 * brand like the Claude Code command. The key is not in it: VS Code asks for it once and keeps it encrypted, so the
 * file may lie in a repository.
 */
export function copilotConfig(origin: string, brand: string): string {
    const name = mcpServerName(brand);
    const config = {
        inputs: [{type: "promptString", id: `${name}-key`, description: `${brand} MCP-Key`, password: true}],
        servers: {
            [name]: {
                type: "http",
                url: `${origin}/mcp`,
                headers: {Authorization: `Bearer \${input:${name}-key}`},
            },
        },
    };
    return JSON.stringify(config, null, 2);
}
