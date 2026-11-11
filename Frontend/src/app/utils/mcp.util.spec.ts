import {
    a2aAddresses,
    backendOrigin,
    claudeCodeCommand,
    claudeConnector,
    copilotConfig,
    mcpServerName,
    MCP_KEY_PLACEHOLDER,
} from "./mcp.util";

describe("mcp.util", () => {
    it("takes the configured API address, else the page's own, without a trailing slash", () => {
        expect(backendOrigin("http://localhost:19100", "http://localhost:4300")).toBe("http://localhost:19100");
        expect(backendOrigin("", "https://ai.example.com")).toBe("https://ai.example.com");
        expect(backendOrigin("https://ai.example.com/", "x")).toBe("https://ai.example.com");
    });

    it("puts the path the app is served under after the page's origin", () => {
        expect(backendOrigin("/alpdesk-ai", "https://intranet.example.com")).toBe(
            "https://intranet.example.com/alpdesk-ai",
        );
        expect(
            claudeCodeCommand(backendOrigin("/alpdesk-ai", "https://intranet.example.com"), "mcp_abc", "Muster AI"),
        ).toContain("https://intranet.example.com/alpdesk-ai/mcp");
    });

    it.each([
        ["Muster AI", "muster-ai"],
        ["Müller Technik", "mueller-technik"],
        ["Straße & Söhne", "strasse-soehne"],
        ["Café Équipe", "cafe-equipe"],
        ["  --Musterfirma.Wissen!  ", "musterfirma-wissen"],
        ["KI 2.0", "ki-2-0"],
        ["%%%", "assistant"],
        ["", "assistant"],
        ["Ein sehr langer Name für den Assistenten der Musterfirma", "ein-sehr-langer-name-fuer-den-assistente"],
        ["Musterfirma Wissensassistent - Technik", "musterfirma-wissensassistent-technik"],
    ])("names the MCP server after the brand %j: %j", (brand, name) => {
        expect(mcpServerName(brand)).toBe(name);
    });

    it("never ends a cut name with a hyphen", () => {
        expect(mcpServerName("Musterfirma Wissensassistent Technik X")).toBe("musterfirma-wissensassistent-technik-x");
        expect(mcpServerName("a".repeat(39) + " b")).toBe("a".repeat(39));
    });

    it("builds the Claude Code command with the brand's name, the MCP address and the key", () => {
        expect(claudeCodeCommand("https://ai.example.com", "mcp_abc", "Muster AI")).toBe(
            'claude mcp add --transport http muster-ai https://ai.example.com/mcp --header "Authorization: Bearer mcp_abc"',
        );
    });

    it("shows the people the command with a placeholder where the key from their administrator goes", () => {
        expect(claudeCodeCommand("https://ai.example.com", MCP_KEY_PLACEHOLDER, "Muster AI")).toBe(
            'claude mcp add --transport http muster-ai https://ai.example.com/mcp --header "Authorization: Bearer <MCP-KEY>"',
        );
    });

    it("gives the connector for claude.ai and Cowork the brand, the MCP address and the whole header value", () => {
        expect(claudeConnector("https://ai.example.com", "mcp_abc", "Muster AI")).toEqual({
            name: "Muster AI",
            url: "https://ai.example.com/mcp",
            header: "authorization",
            value: "Bearer mcp_abc",
        });
    });

    it("keeps the space after Bearer - Claude sends the value exactly as typed", () => {
        expect(claudeConnector("https://ai.example.com", MCP_KEY_PLACEHOLDER, "Muster AI").value).toBe(
            "Bearer <MCP-KEY>",
        );
        expect(
            claudeConnector(backendOrigin("/alpdesk-ai", "https://intranet.example.com"), "mcp_abc", "Muster AI").url,
        ).toBe("https://intranet.example.com/alpdesk-ai/mcp");
    });

    it("builds the VS Code configuration for Copilot with the brand's name, without the key - VS Code asks for it", () => {
        const config = JSON.parse(copilotConfig("https://ai.example.com", "Muster AI"));
        expect(config.servers).toEqual({
            "muster-ai": {
                type: "http",
                url: "https://ai.example.com/mcp",
                headers: {Authorization: "Bearer ${input:muster-ai-key}"},
            },
        });
        expect(config.inputs).toEqual([
            {type: "promptString", id: "muster-ai-key", description: "Muster AI MCP-Key", password: true},
        ]);
        expect(copilotConfig("https://ai.example.com", "Muster AI")).not.toContain("mcp_");
    });
});

describe("a2aAddresses", () => {
    it("names /a2a and the card below the context path", () => {
        expect(a2aAddresses("https://ai.example.com/alpdesk-ai")).toEqual({
            address: "https://ai.example.com/alpdesk-ai/a2a",
            card: "https://ai.example.com/alpdesk-ai/.well-known/agent-card.json",
        });
    });
});
