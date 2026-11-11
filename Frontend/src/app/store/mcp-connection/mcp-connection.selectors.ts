import {createFeatureSelector, createSelector} from "@ngrx/store";
import {McpConnectionState} from "./mcp-connection.reducer";

export const selectMcpConnectionState = createFeatureSelector<McpConnectionState>("mcpConnection");

export const selectConnections = createSelector(selectMcpConnectionState, state => state.connections);

export const selectConnectionsLoading = createSelector(selectMcpConnectionState, state => state.loading);

export const selectChecks = createSelector(selectMcpConnectionState, state => state.checks);

export const selectCheckingId = createSelector(selectMcpConnectionState, state => state.checkingId);

export const selectReloadingId = createSelector(selectMcpConnectionState, state => state.reloadingId);

/** The users' levels per connection id - a connection not opened yet has none. */
export const selectAccess = createSelector(selectMcpConnectionState, state => state.access);
