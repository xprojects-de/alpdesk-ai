import {McpTool} from "../models/mcp.model";
import {
    buildMcpConnectionRequest,
    isWebAddress,
    McpConnectionFormValue,
    signInChip,
    signInCallback,
    signInReturnUrl,
    toolState,
} from "./mcp-connection.util";

describe("buildMcpConnectionRequest", () => {
    const form: McpConnectionFormValue = {
        name: " Buchhaltung ",
        baseUrl: " https://software.example.com/mcp ",
        apiKey: "",
        keyShown: true,
        removeApiKey: false,
        protocol: "MCP",
        answersChat: false,
        clientId: "",
        clientSecret: "",
    };

    it("trims name and address and leaves an empty key out - the stored one stays", () => {
        expect(buildMcpConnectionRequest(form)).toEqual({
            name: "Buchhaltung",
            baseUrl: "https://software.example.com/mcp",
            protocol: "MCP",
        });
    });

    it("sends the protocol the dialog shows - A2A for an agent", () => {
        expect(buildMcpConnectionRequest({...form, protocol: "A2A"}).protocol).toBe("A2A");
    });

    it("sends whether the agent answers the chat for an A2A agent only", () => {
        expect(buildMcpConnectionRequest({...form, protocol: "A2A", answersChat: true}).answersChat).toBe(true);
        expect(buildMcpConnectionRequest({...form, protocol: "MCP", answersChat: true}).answersChat).toBeUndefined();
    });

    it("sends a new key", () => {
        expect(buildMcpConnectionRequest({...form, apiKey: " key-123 "}).apiKey).toBe("key-123");
    });

    it("never sends a key whose field is hidden - a signed-in connection keeps its sign-in", () => {
        expect(buildMcpConnectionRequest({...form, apiKey: "key-123", keyShown: false}).apiKey).toBeUndefined();
    });

    it("sends a client id from the server's portal - its secret only with it", () => {
        expect(buildMcpConnectionRequest({...form, clientId: " portal-client ", clientSecret: " s3cret "})).toEqual(
            expect.objectContaining({clientId: "portal-client", clientSecret: "s3cret"}),
        );
        expect(buildMcpConnectionRequest({...form, clientSecret: "s3cret"}).clientSecret).toBeUndefined();
        expect(buildMcpConnectionRequest(form).clientId).toBeUndefined();
    });

    it("removing the key wins over a new one", () => {
        const request = buildMcpConnectionRequest({...form, apiKey: "key-123", removeApiKey: true});
        expect(request.removeApiKey).toBe(true);
        expect(request.apiKey).toBeUndefined();
    });
});

describe("toolState", () => {
    const tool: McpTool = {id: 1, name: "find", description: "", changes: false, enabled: true, offered: true};

    it.each([
        [{}, "mcpConnections.toolState.on"],
        [{changes: true}, "mcpConnections.toolState.onWithConfirmation"],
        [{enabled: false}, "mcpConnections.toolState.off"],
        [{offered: false}, "mcpConnections.toolState.gone"],
    ])("%o -> %s", (change, expected) => {
        expect(toolState({...tool, ...change})).toBe(expected);
    });
});

describe("the OAuth sign-in", () => {
    it("comes back to this page, absolute, without its query", () => {
        expect(signInReturnUrl({origin: "https://ai.example.com", pathname: "/alpdesk-ai/app/software"})).toBe(
            "https://ai.example.com/alpdesk-ai/app/software",
        );
    });

    it("shows signed in as ok, a running sign-in as info, everything else as a sign-in needed", () => {
        const base = {
            scopesGranted: [],
            scopesOffered: [],
            expiresAt: null,
            lastError: null,
            issuer: "sign-in.example.com",
            clientIdSource: null,
            redirectUri: null,
            clientIdNeeded: false,
        };
        expect(signInChip({...base, status: "SIGNED_IN"}).tone).toBe("ok");
        expect(signInChip({...base, status: "PENDING"}).tone).toBe("info");
        expect(signInChip({...base, status: "AVAILABLE"}).label).toBe("mcpConnections.signIn.needed");
        expect(signInChip({...base, status: "SIGN_IN_NEEDED"}).tone).toBe("warn");
    });

    it("reads the server's answer the callback passed on - for this page to complete; nothing without one", () => {
        expect(
            signInCallback("?connection=12&signIn=complete&code=abc&state=xyz&iss=https%3A%2F%2Fa.example.com"),
        ).toEqual({connectionId: 12, callback: "code=abc&state=xyz&iss=https%3A%2F%2Fa.example.com"});
        expect(signInCallback("?connection=12&signIn=complete&error=access_denied&state=xyz")?.callback).toBe(
            "error=access_denied&state=xyz",
        );
        expect(signInCallback("")).toBeNull();
        expect(signInCallback("?connection=12&signIn=ok")).toBeNull();
        expect(signInCallback("?connection=x&signIn=complete&code=a")).toBeNull();
    });

    it("leaves for a web address only - never for one that would run in this page", () => {
        expect(isWebAddress("https://sign-in.example.com/authorize?state=1")).toBe(true);
        expect(isWebAddress("HTTP://sign-in.example.com/authorize")).toBe(true);
        expect(isWebAddress("javascript://sign-in.example.com/%0Aalert(1)//?state=1")).toBe(false);
        expect(isWebAddress("javascript:alert(1)")).toBe(false);
        expect(isWebAddress("data:text/html,x")).toBe(false);
        expect(isWebAddress("/authorize")).toBe(false);
    });
});
