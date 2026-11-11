import {ApiKey, Tenant} from "../../models/tenant.model";
import * as McpConnectionActions from "../mcp-connection/mcp-connection.actions";
import * as TenantActions from "./tenant.actions";
import {initialState, tenantReducer} from "./tenant.reducer";

describe("tenantReducer - API keys", () => {
    const key = (id: number, keepsHistory = true): ApiKey => ({
        id,
        name: `Key ${id}`,
        keyPrefix: "ai_abc",
        keepsHistory,
        webSearch: false,
        createdAt: "2026-10-07T08:00:00Z",
        lastUsedAt: null,
        areaIds: [],
        taskIds: [],
        connectionIds: [3, 4],
    });

    it("replaces a changed key with the backend's answer - while it saves the list shows it is loading", () => {
        let state = tenantReducer(initialState, TenantActions.loadApiKeys({scope: "self"}));
        state = tenantReducer(state, TenantActions.loadApiKeysSuccess({scope: "self", keys: [key(1), key(2)]}));
        state = tenantReducer(
            state,
            TenantActions.updateApiKey({scope: "self", keyId: 2, request: {webSearch: true, connectionIds: [4]}}),
        );
        expect(state.apiKeys.loading).toBe(true);
        state = tenantReducer(
            state,
            TenantActions.updateApiKeySuccess({
                scope: "self",
                key: {...key(2, false), webSearch: true, connectionIds: [4]},
            }),
        );
        expect(state.apiKeys.keys.map(k => [k.keepsHistory, k.webSearch, k.connectionIds])).toEqual([
            [true, false, [3, 4]],
            [false, true, [4]],
        ]);
        expect(state.apiKeys.loading).toBe(false);
        expect(tenantReducer(state, TenantActions.updateApiKeySuccess({scope: 7, key: key(1, false)}))).toBe(state);
    });

    it("drops a deleted connection from every key of the tenant shown", () => {
        let state = tenantReducer(initialState, TenantActions.loadApiKeys({scope: "self"}));
        state = tenantReducer(state, TenantActions.loadApiKeysSuccess({scope: "self", keys: [key(1), key(2)]}));
        expect(
            tenantReducer(state, McpConnectionActions.deleteConnectionSuccess({scope: 1, connectionId: 4})).apiKeys
                .keys[1].connectionIds,
        ).toEqual([3, 4]);
        state = tenantReducer(state, McpConnectionActions.deleteConnectionSuccess({scope: "self", connectionId: 4}));
        expect(state.apiKeys.keys.map(k => k.connectionIds)).toEqual([[3], [3]]);
    });

    it("keeps the keys as they were when a change fails", () => {
        let state = tenantReducer(initialState, TenantActions.loadApiKeys({scope: 3}));
        state = tenantReducer(state, TenantActions.loadApiKeysSuccess({scope: 3, keys: [key(1)]}));
        state = tenantReducer(state, TenantActions.updateApiKey({scope: 3, keyId: 1, request: {keepsHistory: false}}));
        state = tenantReducer(state, TenantActions.updateApiKeyFailure({error: "x"}));

        expect(state.apiKeys.keys).toEqual([key(1)]);
        expect(state.apiKeys.loading).toBe(false);
    });
});

describe("tenantReducer - sandbox check", () => {
    const tenant = (id: number): Tenant => ({
        id,
        name: `Firma ${id}`,
        active: true,
        contactEmail: null,
        notes: null,
        databaseName: "tenant_ab12",
        globalKeysAllowed: false,
        tasksAllowed: false,
        a2aAllowed: false,
        codeExecution: true,
        sandboxUrls: "http://a:8080",
        sandboxTokenHint: "…wxyz",
        embeddingModelId: null,
        rerankingModelId: null,
        searchLanguage: "SIMPLE",
        createdAt: "2026-10-11T08:00:00Z",
        updatedAt: "2026-10-11T08:00:00Z",
    });
    const reachable = [{url: "http://a:8080", status: "reachable" as const, video: true, extras: true}];

    it("keeps the check of the tenant asked for, and ignores an answer for another one", () => {
        let state = tenantReducer(initialState, TenantActions.checkSandbox({tenantId: 1}));
        expect(state.sandboxCheck).toEqual({tenantId: 1, instances: null, extrasAgree: true, loading: true});

        expect(
            tenantReducer(
                state,
                TenantActions.checkSandboxSuccess({tenantId: 2, instances: reachable, extrasAgree: true}),
            ),
        ).toBe(state);
        state = tenantReducer(
            state,
            TenantActions.checkSandboxSuccess({tenantId: 1, instances: reachable, extrasAgree: false}),
        );
        expect(state.sandboxCheck).toEqual({tenantId: 1, instances: reachable, extrasAgree: false, loading: false});
    });

    it("forgets the check when the tenant is changed or the check fails", () => {
        let state = tenantReducer(initialState, TenantActions.loadTenantsSuccess({tenants: [tenant(1), tenant(2)]}));
        state = tenantReducer(state, TenantActions.checkSandbox({tenantId: 1}));
        state = tenantReducer(
            state,
            TenantActions.checkSandboxSuccess({tenantId: 1, instances: reachable, extrasAgree: true}),
        );

        expect(
            tenantReducer(state, TenantActions.updateTenantSuccess({tenant: tenant(2)})).sandboxCheck,
        ).not.toBeNull();
        expect(tenantReducer(state, TenantActions.updateTenantSuccess({tenant: tenant(1)})).sandboxCheck).toBeNull();
        expect(tenantReducer(state, TenantActions.checkSandboxFailure({error: "x"})).sandboxCheck).toBeNull();
    });
});

describe("tenantReducer - search language", () => {
    const tenant = (id: number, searchLanguage: Tenant["searchLanguage"]): Tenant => ({
        id,
        name: `Firma ${id}`,
        active: true,
        contactEmail: null,
        notes: null,
        databaseName: "tenant_ab12",
        globalKeysAllowed: false,
        tasksAllowed: false,
        a2aAllowed: false,
        codeExecution: false,
        sandboxUrls: null,
        sandboxTokenHint: null,
        embeddingModelId: null,
        rerankingModelId: null,
        searchLanguage,
        createdAt: "2026-10-11T08:00:00Z",
        updatedAt: "2026-10-11T08:00:00Z",
    });

    it("puts the tenant with its new language in place of the old one", () => {
        const state = tenantReducer(
            initialState,
            TenantActions.loadTenantsSuccess({tenants: [tenant(1, "GERMAN"), tenant(2, "GERMAN")]}),
        );

        const changed = tenantReducer(state, TenantActions.setSearchLanguageSuccess({tenant: tenant(1, "ENGLISH")}));

        expect(changed.tenants.map(t => t.searchLanguage)).toEqual(["ENGLISH", "GERMAN"]);
    });
});
