import {createReducer, on} from "@ngrx/store";
import {ApiKey, SandboxInstance, Tenant, TenantScope} from "../../models/tenant.model";
import * as McpConnectionActions from "../mcp-connection/mcp-connection.actions";
import * as TenantActions from "./tenant.actions";

export interface TenantState {
    tenants: Tenant[];
    loading: boolean;
    error: string | null;
    /** The client software's API keys of the tenant on screen - one tenant at a time. */
    apiKeys: {scope: TenantScope | null; keys: ApiKey[]; loading: boolean};
    /** The last check of a tenant's sandbox - one tenant at a time; null: not checked. */
    sandboxCheck: {
        tenantId: number;
        instances: SandboxInstance[] | null;
        /** Whether the instances carry the same README of their own programs (or all none). */
        extrasAgree: boolean;
        loading: boolean;
    } | null;
}

export const initialState: TenantState = {
    tenants: [],
    loading: false,
    error: null,
    apiKeys: {scope: null, keys: [], loading: false},
    sandboxCheck: null,
};

const byName = (a: Tenant, b: Tenant) => a.name.localeCompare(b.name, "de");

export const tenantReducer = createReducer(
    initialState,

    on(
        TenantActions.loadTenants,
        TenantActions.createTenant,
        TenantActions.updateTenant,
        TenantActions.deleteTenant,
        state => ({...state, loading: true, error: null}),
    ),
    on(
        TenantActions.loadTenantsFailure,
        TenantActions.createTenantFailure,
        TenantActions.updateTenantFailure,
        TenantActions.deleteTenantFailure,
        (state, {error}) => ({...state, loading: false, error}),
    ),
    on(TenantActions.loadTenantsSuccess, (state, {tenants}) => ({...state, tenants, loading: false})),
    on(TenantActions.createTenantSuccess, (state, {tenant}) => ({
        ...state,
        tenants: [...state.tenants, tenant].sort(byName),
        loading: false,
    })),
    on(TenantActions.updateTenantSuccess, (state, {tenant}) => ({
        ...state,
        tenants: state.tenants.map(t => (t.id === tenant.id ? tenant : t)).sort(byName),
        loading: false,
        // Its addresses or token may have changed: the check found what was there before.
        sandboxCheck: state.sandboxCheck?.tenantId === tenant.id ? null : state.sandboxCheck,
    })),
    on(TenantActions.checkSandbox, (state, {tenantId}) => ({
        ...state,
        sandboxCheck: {tenantId, instances: null, extrasAgree: true, loading: true},
    })),
    on(TenantActions.checkSandboxSuccess, (state, {tenantId, instances, extrasAgree}) =>
        state.sandboxCheck?.tenantId === tenantId
            ? {...state, sandboxCheck: {tenantId, instances, extrasAgree, loading: false}}
            : state,
    ),
    on(TenantActions.checkSandboxFailure, state => ({...state, sandboxCheck: null})),
    on(
        TenantActions.setEmbeddingModelSuccess,
        TenantActions.setSearchLanguageSuccess,
        TenantActions.setRerankingModelSuccess,
        (state, {tenant}) => ({
            ...state,
            tenants: state.tenants.map(t => (t.id === tenant.id ? tenant : t)),
        }),
    ),
    on(TenantActions.deleteTenantSuccess, (state, {id}) => ({
        ...state,
        tenants: state.tenants.filter(t => t.id !== id),
        loading: false,
    })),

    on(TenantActions.loadApiKeys, (state, {scope}) => ({
        ...state,
        apiKeys: {
            scope,
            keys: state.apiKeys.scope === scope ? state.apiKeys.keys : [],
            loading: true,
        },
    })),
    on(TenantActions.createApiKey, TenantActions.updateApiKey, TenantActions.revokeApiKey, state => ({
        ...state,
        apiKeys: {...state.apiKeys, loading: true},
    })),
    on(
        TenantActions.loadApiKeysFailure,
        TenantActions.createApiKeyFailure,
        TenantActions.updateApiKeyFailure,
        TenantActions.revokeApiKeyFailure,
        state => ({...state, apiKeys: {...state.apiKeys, loading: false}}),
    ),
    on(TenantActions.loadApiKeysSuccess, (state, {scope, keys}) =>
        state.apiKeys.scope === scope ? {...state, apiKeys: {scope, keys, loading: false}} : state,
    ),
    on(TenantActions.createApiKeySuccess, (state, {scope, key}) =>
        state.apiKeys.scope === scope
            ? {...state, apiKeys: {...state.apiKeys, keys: [...state.apiKeys.keys, key], loading: false}}
            : state,
    ),
    on(TenantActions.updateApiKeySuccess, (state, {scope, key}) =>
        state.apiKeys.scope === scope
            ? {
                  ...state,
                  apiKeys: {
                      ...state.apiKeys,
                      keys: state.apiKeys.keys.map(k => (k.id === key.id ? key : k)),
                      loading: false,
                  },
              }
            : state,
    ),
    on(TenantActions.revokeApiKeySuccess, (state, {scope, keyId}) =>
        state.apiKeys.scope === scope
            ? {
                  ...state,
                  apiKeys: {...state.apiKeys, keys: state.apiKeys.keys.filter(k => k.id !== keyId), loading: false},
              }
            : state,
    ),
    on(McpConnectionActions.deleteConnectionSuccess, (state, {scope, connectionId}) =>
        state.apiKeys.scope === scope
            ? {
                  ...state,
                  apiKeys: {
                      ...state.apiKeys,
                      keys: state.apiKeys.keys.map(k => ({
                          ...k,
                          connectionIds: k.connectionIds.filter(id => id !== connectionId),
                      })),
                  },
              }
            : state,
    ),
);
