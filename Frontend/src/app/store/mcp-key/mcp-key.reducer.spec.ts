import {McpKey} from "../../models/mcp.model";
import * as AreaActions from "../knowledge-area/knowledge-area.actions";
import * as McpConnectionActions from "../mcp-connection/mcp-connection.actions";
import * as TaskActions from "../task/task.actions";
import * as McpKeyActions from "./mcp-key.actions";
import {initialState, mcpKeyReducer} from "./mcp-key.reducer";

describe("mcpKeyReducer", () => {
    const standard: McpKey = {
        id: 1,
        name: "Standard",
        description: null,
        keyPrefix: "mcp_abcdefgh",
        webSearch: false,
        areaIds: [],
        connectionIds: [],
        taskIds: [],
        createdAt: "",
        lastUsedAt: null,
        mcp: true,
        a2a: false,
    };
    const development: McpKey = {
        ...standard,
        id: 2,
        name: "Entwicklung",
        description: null,
        keyPrefix: "mcp_ijklmnop",
        areaIds: [7, 8],
        connectionIds: [3, 4],
        taskIds: [7, 8],
    };

    it("adds a created key and shows it once with its name, until dismissed", () => {
        let state = mcpKeyReducer(initialState, McpKeyActions.loadMcpKeys({scope: 1}));
        state = mcpKeyReducer(state, McpKeyActions.loadMcpKeysSuccess({scope: 1, keys: [standard]}));
        state = mcpKeyReducer(
            state,
            McpKeyActions.createMcpKeySuccess({scope: 1, key: development, plainKey: "mcp_secret"}),
        );
        expect(state.keys.map(key => key.name)).toEqual(["Standard", "Entwicklung"]);
        expect(state.created).toEqual({name: "Entwicklung", plainKey: "mcp_secret", mcp: true, a2a: false});
        expect(mcpKeyReducer(state, McpKeyActions.dismissCreatedMcpKey()).created).toBeNull();
    });

    it("a renewed key takes the place of the old one and is shown once; a changed one replaces it; a deleted one goes", () => {
        let state = mcpKeyReducer(initialState, McpKeyActions.loadMcpKeys({scope: 1}));
        state = mcpKeyReducer(state, McpKeyActions.loadMcpKeysSuccess({scope: 1, keys: [standard, development]}));
        const renewed = {...standard, keyPrefix: "mcp_qrstuvwx"};
        state = mcpKeyReducer(state, McpKeyActions.renewMcpKeySuccess({scope: 1, key: renewed, plainKey: "mcp_new"}));
        expect(state.keys).toEqual([renewed, development]);
        expect(state.created).toEqual({name: "Standard", plainKey: "mcp_new", mcp: true, a2a: false});

        state = mcpKeyReducer(
            state,
            McpKeyActions.updateMcpKeySuccess({scope: 1, key: {...development, webSearch: true}}),
        );
        expect(state.keys[1].webSearch).toBe(true);
        expect(mcpKeyReducer(state, McpKeyActions.deleteMcpKeySuccess({scope: 1, keyId: 1})).keys).toEqual([
            state.keys[1],
        ]);
    });

    it("drops a deleted area from every key", () => {
        let state = mcpKeyReducer(initialState, McpKeyActions.loadMcpKeys({scope: 1}));
        state = mcpKeyReducer(state, McpKeyActions.loadMcpKeysSuccess({scope: 1, keys: [development]}));
        expect(mcpKeyReducer(state, AreaActions.deleteAreaSuccess({scope: 1, id: 7})).keys[0].areaIds).toEqual([8]);
    });

    it("drops a deleted connection from every key", () => {
        let state = mcpKeyReducer(initialState, McpKeyActions.loadMcpKeys({scope: 1}));
        state = mcpKeyReducer(state, McpKeyActions.loadMcpKeysSuccess({scope: 1, keys: [development]}));
        const deleted = mcpKeyReducer(state, McpConnectionActions.deleteConnectionSuccess({scope: 1, connectionId: 3}));
        expect(deleted.keys[0].connectionIds).toEqual([4]);
        expect(
            mcpKeyReducer(state, McpConnectionActions.deleteConnectionSuccess({scope: 2, connectionId: 3})).keys[0]
                .connectionIds,
        ).toEqual([3, 4]);
    });

    it("drops a deleted task from every key", () => {
        let state = mcpKeyReducer(initialState, McpKeyActions.loadMcpKeys({scope: 1}));
        state = mcpKeyReducer(state, McpKeyActions.loadMcpKeysSuccess({scope: 1, keys: [development]}));
        expect(mcpKeyReducer(state, TaskActions.deleteTaskSuccess({scope: 1, taskId: 7})).keys[0].taskIds).toEqual([8]);
        expect(mcpKeyReducer(state, TaskActions.deleteTaskSuccess({scope: 2, taskId: 7})).keys[0].taskIds).toEqual([
            7, 8,
        ]);
    });

    it("forgets another tenant's keys when one is opened and ignores late results for it", () => {
        let state = mcpKeyReducer(initialState, McpKeyActions.loadMcpKeys({scope: 1}));
        state = mcpKeyReducer(
            state,
            McpKeyActions.createMcpKeySuccess({scope: 1, key: standard, plainKey: "mcp_secret"}),
        );
        state = mcpKeyReducer(state, McpKeyActions.loadMcpKeys({scope: 2}));
        expect(state.keys).toEqual([]);
        expect(state.created).toBeNull();
        expect(mcpKeyReducer(state, McpKeyActions.loadMcpKeysSuccess({scope: 1, keys: [standard]})).keys).toEqual([]);
        expect(
            mcpKeyReducer(state, McpKeyActions.renewMcpKeySuccess({scope: 1, key: standard, plainKey: "x"})).created,
        ).toBeNull();
    });

    it("ignores keys answered after the logout, before the next login opened them", () => {
        expect(mcpKeyReducer(initialState, McpKeyActions.loadMcpKeysSuccess({scope: "self", keys: [standard]}))).toBe(
            initialState,
        );
    });
});
