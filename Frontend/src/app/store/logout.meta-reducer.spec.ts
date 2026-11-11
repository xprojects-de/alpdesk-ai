import {ActionReducer, combineReducers} from "@ngrx/store";
import * as AuthActions from "./auth/auth.actions";
import {clearOnLogout} from "./logout.meta-reducer";
import * as TenantActions from "./tenant/tenant.actions";
import {initialState as tenantInitial, tenantReducer} from "./tenant/tenant.reducer";
import {Tenant} from "../models/tenant.model";

describe("clearOnLogout", () => {
    const version = (state = "", action: {type: string; version?: string}) =>
        action.type === "[Test] Version" ? action.version! : state;
    const reducer = clearOnLogout(
        combineReducers({tenant: tenantReducer, version}) as unknown as ActionReducer<Record<string, unknown>>,
    );
    const tenant: Tenant = {
        id: 1,
        name: "Musterfirma GmbH",
        active: true,
        contactEmail: null,
        notes: null,
        databaseName: "tenant_1",
        globalKeysAllowed: false,
        tasksAllowed: false,
        a2aAllowed: false,
        codeExecution: false,
        sandboxUrls: null,
        sandboxTokenHint: null,
        embeddingModelId: null,
        rerankingModelId: null,
        searchLanguage: "SIMPLE",
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
    };

    it("resets every slice on logout, keeps the version", () => {
        let state = reducer(undefined, {type: "@@init"});
        state = reducer(state, TenantActions.loadTenantsSuccess({tenants: [tenant]}));
        state = reducer(state, {type: "[Test] Version", version: "1.2.3"} as never);
        expect((state["tenant"] as typeof tenantInitial).tenants).toEqual([tenant]);

        state = reducer(state, AuthActions.logoutSuccess());

        expect(state["tenant"]).toEqual(tenantInitial);
        expect(state["version"]).toBe("1.2.3");
    });

    it("resets every slice at the switch to another tenant too - nothing of the old tenant stays", () => {
        let state = reducer(undefined, {type: "@@init"});
        state = reducer(state, TenantActions.loadTenantsSuccess({tenants: [tenant]}));
        state = reducer(state, {type: "[Test] Version", version: "1.2.3"} as never);

        state = reducer(
            state,
            AuthActions.switchTenantSuccess({response: {access_token: "jwt", roles: ["ROLE_TENANT_USER"]}}),
        );

        expect(state["tenant"]).toEqual(tenantInitial);
        expect(state["version"]).toBe("1.2.3");
    });

    it("leaves the state alone for other actions", () => {
        const state = reducer(
            reducer(undefined, {type: "@@init"}),
            TenantActions.loadTenantsSuccess({tenants: [tenant]}),
        );
        expect(reducer(state, {type: "[Test] Other"})).toBe(state);
    });
});
