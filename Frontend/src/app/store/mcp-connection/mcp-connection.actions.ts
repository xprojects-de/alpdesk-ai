import {createAction, props} from "@ngrx/store";
import {
    McpAccess,
    McpConnection,
    McpConnectionRequest,
    McpPrompt,
    McpTool,
    McpConnectionCheck,
} from "../../models/mcp.model";
import {TenantScope} from "../../models/tenant.model";

export const loadConnections = createAction("[MCP Connection] Load", props<{scope: TenantScope}>());
export const loadConnectionsSuccess = createAction(
    "[MCP Connection] Load Success",
    props<{scope: TenantScope; connections: McpConnection[]}>(),
);
export const loadConnectionsFailure = createAction("[MCP Connection] Load Failure", props<{error: string}>());

export const createConnection = createAction(
    "[MCP Connection] Create",
    props<{scope: TenantScope; request: McpConnectionRequest}>(),
);
export const createConnectionSuccess = createAction(
    "[MCP Connection] Create Success",
    props<{scope: TenantScope; connection: McpConnection}>(),
);
export const createConnectionFailure = createAction("[MCP Connection] Create Failure", props<{error: string}>());

export const updateConnection = createAction(
    "[MCP Connection] Update",
    props<{scope: TenantScope; connectionId: number; request: McpConnectionRequest}>(),
);
export const updateConnectionSuccess = createAction(
    "[MCP Connection] Update Success",
    props<{scope: TenantScope; connection: McpConnection}>(),
);
export const updateConnectionFailure = createAction("[MCP Connection] Update Failure", props<{error: string}>());

export const signIn = createAction(
    "[MCP Connection] Sign In",
    props<{scope: TenantScope; connectionId: number; returnUrl: string}>(),
);
export const signInStarted = createAction("[MCP Connection] Sign In Started", props<{authorizationUrl: string}>());
export const signInFailure = createAction("[MCP Connection] Sign In Failure", props<{error: string}>());

/** Back on the page: completes the sign-in with the server's answer our callback passed on. */
export const completeSignIn = createAction(
    "[MCP Connection] Complete Sign In",
    props<{scope: TenantScope; connectionId: number; callback: string}>(),
);
export const completeSignInSuccess = createAction(
    "[MCP Connection] Complete Sign In Success",
    props<{scope: TenantScope; connection: McpConnection}>(),
);
export const completeSignInFailure = createAction(
    "[MCP Connection] Complete Sign In Failure",
    props<{error: string}>(),
);

export const removeSignIn = createAction(
    "[MCP Connection] Remove Sign In",
    props<{scope: TenantScope; connectionId: number}>(),
);
export const removeSignInSuccess = createAction(
    "[MCP Connection] Remove Sign In Success",
    props<{scope: TenantScope; connectionId: number}>(),
);
export const removeSignInFailure = createAction("[MCP Connection] Remove Sign In Failure", props<{error: string}>());

export const deleteConnection = createAction(
    "[MCP Connection] Delete",
    props<{scope: TenantScope; connectionId: number}>(),
);
export const deleteConnectionSuccess = createAction(
    "[MCP Connection] Delete Success",
    props<{scope: TenantScope; connectionId: number}>(),
);
export const deleteConnectionFailure = createAction("[MCP Connection] Delete Failure", props<{error: string}>());

/** Reads an A2A agent's card and times it - the check button. */
export const checkConnection = createAction(
    "[MCP Connection] Check",
    props<{scope: TenantScope; connectionId: number}>(),
);
export const checkConnectionSuccess = createAction(
    "[MCP Connection] Check Success",
    props<{scope: TenantScope; connectionId: number; check: McpConnectionCheck}>(),
);
export const checkConnectionFailure = createAction(
    "[MCP Connection] Check Failure",
    props<{connectionId: number; error: string}>(),
);

/** Reads the software's tools again. */
export const reloadTools = createAction(
    "[MCP Connection] Reload Tools",
    props<{scope: TenantScope; connectionId: number}>(),
);
export const reloadToolsSuccess = createAction(
    "[MCP Connection] Reload Tools Success",
    props<{scope: TenantScope; connection: McpConnection}>(),
);
export const reloadToolsFailure = createAction(
    "[MCP Connection] Reload Tools Failure",
    props<{connectionId: number; error: string}>(),
);

export const updateTool = createAction(
    "[MCP Connection] Update Tool",
    props<{scope: TenantScope; connectionId: number; toolId: number; change: {enabled?: boolean; changes?: boolean}}>(),
);
export const updateToolSuccess = createAction(
    "[MCP Connection] Update Tool Success",
    props<{scope: TenantScope; connectionId: number; tool: McpTool}>(),
);
export const updateToolFailure = createAction("[MCP Connection] Update Tool Failure", props<{error: string}>());

export const updatePrompt = createAction(
    "[MCP Connection] Update Prompt",
    props<{scope: TenantScope; connectionId: number; promptId: number; enabled: boolean}>(),
);
export const updatePromptSuccess = createAction(
    "[MCP Connection] Update Prompt Success",
    props<{scope: TenantScope; connectionId: number; prompt: McpPrompt}>(),
);
export const updatePromptFailure = createAction("[MCP Connection] Update Prompt Failure", props<{error: string}>());

/** Whether MCP keys with the connection get it directly ("Direkt an MCP-Clients"). */
export const setPassThrough = createAction(
    "[MCP Connection] Set Pass Through",
    props<{scope: TenantScope; connectionId: number; passesThrough: boolean}>(),
);
export const setPassThroughSuccess = createAction(
    "[MCP Connection] Set Pass Through Success",
    props<{scope: TenantScope; connection: McpConnection}>(),
);
export const setPassThroughFailure = createAction(
    "[MCP Connection] Set Pass Through Failure",
    props<{error: string}>(),
);

export const loadAccess = createAction(
    "[MCP Connection] Load Access",
    props<{scope: TenantScope; connectionId: number}>(),
);
export const loadAccessSuccess = createAction(
    "[MCP Connection] Load Access Success",
    props<{scope: TenantScope; connectionId: number; access: McpAccess[]}>(),
);
export const loadAccessFailure = createAction("[MCP Connection] Load Access Failure", props<{error: string}>());
