import {createReducer, on} from "@ngrx/store";
import {McpKey} from "../../models/mcp.model";
import {TenantScope} from "../../models/tenant.model";
import * as AreaActions from "../knowledge-area/knowledge-area.actions";
import * as McpConnectionActions from "../mcp-connection/mcp-connection.actions";
import * as TaskActions from "../task/task.actions";
import * as McpKeyActions from "./mcp-key.actions";

/** A key just created or renewed: shown until dismissed, never again. */
export interface CreatedMcpKey {
    name: string;
    plainKey: string;
    /** Its doors - what the panel shows how to connect. */
    mcp: boolean;
    a2a: boolean;
}

/** One tenant's MCP keys at a time: the tenant admin's own, or those of the tenant the admin opened. */
export interface McpKeyState {
    scope: TenantScope | null;
    keys: McpKey[];
    loading: boolean;
    created: CreatedMcpKey | null;
}

export const initialState: McpKeyState = {
    scope: null,
    keys: [],
    loading: false,
    created: null,
};

/** The key in place of the one with its id - added at the end when it is new. */
function withKey(keys: McpKey[], key: McpKey): McpKey[] {
    return keys.some(k => k.id === key.id) ? keys.map(k => (k.id === key.id ? key : k)) : [...keys, key];
}

export const mcpKeyReducer = createReducer(
    initialState,

    on(McpKeyActions.loadMcpKeys, (state, {scope}) => ({
        ...state,
        scope,
        keys: state.scope === scope ? state.keys : [],
        created: state.scope === scope ? state.created : null,
        loading: true,
    })),
    // Results for another tenant (the admin opened another one's meanwhile) are ignored.
    on(McpKeyActions.loadMcpKeysSuccess, (state, {scope, keys}) =>
        state.scope === scope ? {...state, keys, loading: false} : state,
    ),
    on(McpKeyActions.loadMcpKeysFailure, state => ({...state, loading: false})),

    on(McpKeyActions.createMcpKeySuccess, McpKeyActions.renewMcpKeySuccess, (state, {scope, key, plainKey}) =>
        state.scope === scope
            ? {
                  ...state,
                  keys: withKey(state.keys, key),
                  created: {name: key.name, plainKey, mcp: key.mcp, a2a: key.a2a},
              }
            : state,
    ),
    on(McpKeyActions.updateMcpKeySuccess, (state, {scope, key}) =>
        state.scope === scope ? {...state, keys: withKey(state.keys, key)} : state,
    ),
    on(McpKeyActions.deleteMcpKeySuccess, (state, {scope, keyId}) =>
        state.scope === scope ? {...state, keys: state.keys.filter(k => k.id !== keyId)} : state,
    ),
    on(McpKeyActions.dismissCreatedMcpKey, state => ({...state, created: null})),
    // The backend drops a deleted area or connection from every key - so does the list.
    on(AreaActions.deleteAreaSuccess, (state, {scope, id}) =>
        state.scope === scope
            ? {...state, keys: state.keys.map(k => ({...k, areaIds: k.areaIds.filter(areaId => areaId !== id)}))}
            : state,
    ),
    on(McpConnectionActions.deleteConnectionSuccess, (state, {scope, connectionId}) =>
        state.scope === scope
            ? {
                  ...state,
                  keys: state.keys.map(k => ({
                      ...k,
                      connectionIds: k.connectionIds.filter(id => id !== connectionId),
                      changingConnectionIds: k.changingConnectionIds.filter(id => id !== connectionId),
                  })),
              }
            : state,
    ),
    // A software that stops going to MCP clients takes the level off its keys - so does the list.
    on(McpConnectionActions.setPassThroughSuccess, (state, {scope, connection}) =>
        state.scope === scope && !connection.passesThrough
            ? {
                  ...state,
                  keys: state.keys.map(k => ({
                      ...k,
                      changingConnectionIds: k.changingConnectionIds.filter(id => id !== connection.id),
                  })),
              }
            : state,
    ),
    on(TaskActions.deleteTaskSuccess, (state, {scope, taskId}) =>
        state.scope === scope
            ? {...state, keys: state.keys.map(k => ({...k, taskIds: k.taskIds.filter(id => id !== taskId)}))}
            : state,
    ),
);
