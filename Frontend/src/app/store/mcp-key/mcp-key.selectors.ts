import {createFeatureSelector, createSelector} from "@ngrx/store";
import {McpKeyState} from "./mcp-key.reducer";

export const selectMcpKeyState = createFeatureSelector<McpKeyState>("mcpKey");

export const selectMcpKeys = createSelector(selectMcpKeyState, state => state.keys);

export const selectMcpKeysLoading = createSelector(selectMcpKeyState, state => state.loading);

export const selectCreatedMcpKey = createSelector(selectMcpKeyState, state => state.created);
