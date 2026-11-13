import {createReducer, on} from "@ngrx/store";
import {McpAccess, McpConnection, McpConnectionCheck} from "../../models/mcp.model";
import {TenantScope} from "../../models/tenant.model";
import * as McpConnectionActions from "./mcp-connection.actions";

/** One tenant's connected software at a time: the tenant admin's own, or the tenant the admin opened. */
export interface McpConnectionState {
    scope: TenantScope | null;
    connections: McpConnection[];
    loading: boolean;
    /** The connection whose tools are being read again. */
    reloadingId: number | null;
    /** The users' levels per connection, loaded when a connection is opened. */
    access: Record<number, McpAccess[]>;
    /** The last check of an A2A agent per connection; the one being checked. */
    checks: Record<number, McpConnectionCheck>;
    checkingId: number | null;
}

export const initialState: McpConnectionState = {
    scope: null,
    connections: [],
    loading: false,
    reloadingId: null,
    access: {},
    checks: {},
    checkingId: null,
};

function replace(connections: McpConnection[], connection: McpConnection): McpConnection[] {
    return connections.map(existing => (existing.id === connection.id ? connection : existing));
}

function byName(connections: McpConnection[]): McpConnection[] {
    return [...connections].sort((a, b) => a.name.localeCompare(b.name));
}

export const mcpConnectionReducer = createReducer(
    initialState,

    on(McpConnectionActions.loadConnections, (state, {scope}) =>
        state.scope === scope ? {...state, loading: true} : {...initialState, scope, loading: true},
    ),
    // Results for another tenant (the admin opened another one's meanwhile) are ignored.
    on(McpConnectionActions.loadConnectionsSuccess, (state, {scope, connections}) =>
        state.scope === scope ? {...state, connections, loading: false} : state,
    ),
    on(McpConnectionActions.loadConnectionsFailure, state => ({...state, loading: false})),

    on(McpConnectionActions.createConnectionSuccess, (state, {scope, connection}) =>
        state.scope === scope ? {...state, connections: byName([...state.connections, connection])} : state,
    ),
    on(
        McpConnectionActions.updateConnectionSuccess,
        McpConnectionActions.reloadToolsSuccess,
        McpConnectionActions.setPassThroughSuccess,
        McpConnectionActions.setPassResourcesSuccess,
        McpConnectionActions.completeSignInSuccess,
        (state, {scope, connection}) =>
            state.scope === scope
                ? {...state, connections: byName(replace(state.connections, connection)), reloadingId: null}
                : state,
    ),
    on(McpConnectionActions.removeSignInSuccess, (state, {scope, connectionId}) =>
        state.scope === scope
            ? {
                  ...state,
                  connections: state.connections.map(connection =>
                      connection.id === connectionId ? {...connection, signIn: null} : connection,
                  ),
              }
            : state,
    ),
    on(McpConnectionActions.deleteConnectionSuccess, (state, {scope, connectionId}) => {
        if (state.scope !== scope) {
            return state;
        }
        const access = {...state.access};
        delete access[connectionId];
        return {...state, connections: state.connections.filter(c => c.id !== connectionId), access};
    }),

    on(McpConnectionActions.checkConnection, (state, {connectionId}) => ({...state, checkingId: connectionId})),
    on(McpConnectionActions.checkConnectionSuccess, (state, {scope, connectionId, check}) =>
        state.scope === scope ? {...state, checks: {...state.checks, [connectionId]: check}, checkingId: null} : state,
    ),
    on(McpConnectionActions.checkConnectionFailure, state => ({...state, checkingId: null})),
    on(McpConnectionActions.reloadTools, (state, {connectionId}) => ({...state, reloadingId: connectionId})),
    on(McpConnectionActions.reloadToolsFailure, state => ({...state, reloadingId: null})),

    on(McpConnectionActions.updatePromptSuccess, (state, {scope, connectionId, prompt}) =>
        state.scope === scope
            ? {
                  ...state,
                  connections: state.connections.map(connection =>
                      connection.id === connectionId
                          ? {...connection, prompts: connection.prompts.map(p => (p.id === prompt.id ? prompt : p))}
                          : connection,
                  ),
              }
            : state,
    ),

    on(McpConnectionActions.updateToolSuccess, (state, {scope, connectionId, tool}) =>
        state.scope === scope
            ? {
                  ...state,
                  connections: state.connections.map(connection =>
                      connection.id === connectionId
                          ? {...connection, tools: connection.tools.map(t => (t.id === tool.id ? tool : t))}
                          : connection,
                  ),
              }
            : state,
    ),

    on(McpConnectionActions.loadAccessSuccess, (state, {scope, connectionId, access}) =>
        state.scope === scope ? {...state, access: {...state.access, [connectionId]: access}} : state,
    ),
);
