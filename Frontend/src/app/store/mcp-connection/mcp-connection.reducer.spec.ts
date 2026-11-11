import {McpAccess, McpConnection, McpPrompt, McpTool} from "../../models/mcp.model";
import * as McpConnectionActions from "./mcp-connection.actions";
import {initialState, mcpConnectionReducer} from "./mcp-connection.reducer";

describe("mcpConnectionReducer", () => {
    const tool: McpTool = {id: 7, name: "find_invoice", description: "", changes: false, enabled: false, offered: true};
    const prompt: McpPrompt = {
        id: 9,
        name: "create_test_program",
        title: null,
        description: "",
        arguments: [],
        enabled: false,
        offered: true,
    };
    const connection = (id: number, name: string, tools: McpTool[] = [], prompts: McpPrompt[] = []): McpConnection => ({
        id,
        name,
        baseUrl: "https://software.example.com/mcp",
        keyHint: null,
        toolsLoadedAt: null,
        toolsError: null,
        promptsError: null,
        passesThrough: false,
        instructions: null,
        protocol: "MCP",
        answersChat: false,
        tools,
        prompts,
        signIn: null,
    });
    const access = (groupId: number, level: McpAccess["level"]): McpAccess => ({
        groupId,
        name: "Gruppe " + groupId,
        wamGroup: null,
        level,
    });

    function loaded(connections: McpConnection[]) {
        const state = mcpConnectionReducer(initialState, McpConnectionActions.loadConnections({scope: 1}));
        return mcpConnectionReducer(state, McpConnectionActions.loadConnectionsSuccess({scope: 1, connections}));
    }

    it("a completed sign-in replaces its connection - another tenant's answer changes nothing", () => {
        let state = loaded([connection(1, "Buchhaltung"), connection(2, "Wiki")]);
        const signedIn = {...connection(1, "Buchhaltung", [tool]), toolsLoadedAt: "2026-11-09T10:00:00Z"};
        state = mcpConnectionReducer(
            state,
            McpConnectionActions.completeSignInSuccess({scope: 2, connection: signedIn}),
        );
        expect(state.connections[0].tools).toEqual([]);
        state = mcpConnectionReducer(
            state,
            McpConnectionActions.completeSignInSuccess({scope: 1, connection: signedIn}),
        );
        expect(state.connections.map(c => c.tools.length)).toEqual([1, 0]);
    });

    it("a removed sign-in leaves its connection without one - another tenant's answer changes nothing", () => {
        const signedIn: McpConnection = {
            ...connection(1, "Buchhaltung"),
            signIn: {
                status: "SIGNED_IN",
                scopesGranted: ["read"],
                scopesOffered: ["read"],
                expiresAt: null,
                lastError: null,
                issuer: "sign-in.example.com",
                clientIdSource: "REGISTERED",
                redirectUri: null,
                clientIdNeeded: false,
            },
        };
        let state = loaded([signedIn]);
        state = mcpConnectionReducer(state, McpConnectionActions.removeSignInSuccess({scope: 2, connectionId: 1}));
        expect(state.connections[0].signIn).not.toBeNull();
        state = mcpConnectionReducer(state, McpConnectionActions.removeSignInSuccess({scope: 1, connectionId: 1}));
        expect(state.connections[0].signIn).toBeNull();
    });

    it("keeps the connections by name; a created, updated or reloaded one takes its place", () => {
        let state = loaded([connection(1, "Buchhaltung")]);
        state = mcpConnectionReducer(
            state,
            McpConnectionActions.createConnectionSuccess({scope: 1, connection: connection(2, "Auftrag")}),
        );
        expect(state.connections.map(c => c.name)).toEqual(["Auftrag", "Buchhaltung"]);

        state = mcpConnectionReducer(state, McpConnectionActions.reloadTools({scope: 1, connectionId: 1}));
        expect(state.reloadingId).toBe(1);
        state = mcpConnectionReducer(
            state,
            McpConnectionActions.reloadToolsSuccess({scope: 1, connection: connection(1, "Buchhaltung", [tool])}),
        );
        expect(state.reloadingId).toBeNull();
        expect(state.connections[1].tools).toEqual([tool]);
    });

    it("a switched tool and a set level replace their old values; deleting drops the connection and its levels", () => {
        let state = loaded([connection(1, "Buchhaltung", [tool])]);
        state = mcpConnectionReducer(
            state,
            McpConnectionActions.updateToolSuccess({scope: 1, connectionId: 1, tool: {...tool, enabled: true}}),
        );
        expect(state.connections[0].tools[0].enabled).toBe(true);

        state = mcpConnectionReducer(
            loaded([connection(1, "Buchhaltung", [tool], [prompt])]),
            McpConnectionActions.updatePromptSuccess({scope: 1, connectionId: 1, prompt: {...prompt, enabled: true}}),
        );
        expect(state.connections[0].prompts[0].enabled).toBe(true);
        state = mcpConnectionReducer(
            state,
            McpConnectionActions.updatePromptSuccess({scope: 2, connectionId: 1, prompt: {...prompt, enabled: false}}),
        );
        expect(state.connections[0].prompts[0].enabled).toBe(true);

        state = mcpConnectionReducer(
            state,
            McpConnectionActions.loadAccessSuccess({
                scope: 1,
                connectionId: 1,
                access: [access(5, null), access(6, null)],
            }),
        );
        expect(state.access[1].map(a => a.level)).toEqual([null, null]);

        state = mcpConnectionReducer(state, McpConnectionActions.deleteConnectionSuccess({scope: 1, connectionId: 1}));
        expect(state.connections).toEqual([]);
        expect(state.access[1]).toBeUndefined();
    });

    it("forgets another tenant's connections when one is opened and ignores late results for it", () => {
        let state = loaded([connection(1, "Buchhaltung")]);
        state = mcpConnectionReducer(state, McpConnectionActions.loadConnections({scope: 2}));
        expect(state.connections).toEqual([]);
        state = mcpConnectionReducer(
            state,
            McpConnectionActions.createConnectionSuccess({scope: 1, connection: connection(3, "Spät")}),
        );
        expect(state.connections).toEqual([]);
    });

    it("passing a connection directly to MCP clients replaces it with the stored one", () => {
        const state = mcpConnectionReducer(
            loaded([connection(1, "Buchhaltung")]),
            McpConnectionActions.setPassThroughSuccess({
                scope: 1,
                connection: {...connection(1, "Buchhaltung"), passesThrough: true},
            }),
        );
        expect(state.connections[0].passesThrough).toBe(true);
    });

    it("ignores connections answered after the logout, before the next login opened them", () => {
        const late = McpConnectionActions.loadConnectionsSuccess({scope: "self", connections: [connection(1, "ERP")]});
        expect(mcpConnectionReducer(initialState, late)).toBe(initialState);
    });
});
