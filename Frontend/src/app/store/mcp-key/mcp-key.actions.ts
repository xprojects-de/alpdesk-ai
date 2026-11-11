import {createAction, props} from "@ngrx/store";
import {McpKey, McpKeyRequest} from "../../models/mcp.model";
import {TenantScope} from "../../models/tenant.model";

export const loadMcpKeys = createAction("[MCP Key] Load", props<{scope: TenantScope}>());
export const loadMcpKeysSuccess = createAction("[MCP Key] Load Success", props<{scope: TenantScope; keys: McpKey[]}>());
export const loadMcpKeysFailure = createAction("[MCP Key] Load Failure", props<{error: string}>());

export const createMcpKey = createAction("[MCP Key] Create", props<{scope: TenantScope; request: McpKeyRequest}>());
export const createMcpKeySuccess = createAction(
    "[MCP Key] Create Success",
    props<{scope: TenantScope; key: McpKey; plainKey: string}>(),
);
export const createMcpKeyFailure = createAction("[MCP Key] Create Failure", props<{error: string}>());

/** Switches its web search or changes its areas. */
export const updateMcpKey = createAction(
    "[MCP Key] Update",
    props<{scope: TenantScope; keyId: number; request: McpKeyRequest}>(),
);
export const updateMcpKeySuccess = createAction("[MCP Key] Update Success", props<{scope: TenantScope; key: McpKey}>());
export const updateMcpKeyFailure = createAction("[MCP Key] Update Failure", props<{error: string}>());

/** A new secret for the key - shown once like a new key. */
export const renewMcpKey = createAction("[MCP Key] Renew", props<{scope: TenantScope; keyId: number}>());
export const renewMcpKeySuccess = createAction(
    "[MCP Key] Renew Success",
    props<{scope: TenantScope; key: McpKey; plainKey: string}>(),
);
export const renewMcpKeyFailure = createAction("[MCP Key] Renew Failure", props<{error: string}>());

export const deleteMcpKey = createAction("[MCP Key] Delete", props<{scope: TenantScope; keyId: number}>());
export const deleteMcpKeySuccess = createAction(
    "[MCP Key] Delete Success",
    props<{scope: TenantScope; keyId: number}>(),
);
export const deleteMcpKeyFailure = createAction("[MCP Key] Delete Failure", props<{error: string}>());

/** The new key was copied - it is not shown again. */
export const dismissCreatedMcpKey = createAction("[MCP Key] Dismiss Created");
