import {TenantUser} from "../../models/tenant-user.model";
import * as TenantUserActions from "./tenant-user.actions";
import * as LanguageActions from "../language/language.actions";
import {initialState, tenantUserReducer} from "./tenant-user.reducer";

describe("tenantUserReducer", () => {
    const user = (id: number, username: string): TenantUser => ({
        id,
        username,
        displayName: username,
        active: true,
        admin: false,
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
        lastLoginAt: null,
        groupIds: [],
        wamGroups: [],
        language: "en",
    });

    it("drops the users of another tenant when a tenant's dialog opens", () => {
        const loaded = tenantUserReducer(
            tenantUserReducer(initialState, TenantUserActions.loadUsers({scope: 1})),
            TenantUserActions.loadUsersSuccess({scope: 1, users: [user(1, "anna")]}),
        );

        const state = tenantUserReducer(loaded, TenantUserActions.loadUsers({scope: 2}));

        expect(state).toEqual({scope: 2, users: [], loading: true, otherTenant: null});
    });

    it("ignores results for a tenant whose dialog is no longer open", () => {
        const state = tenantUserReducer(initialState, TenantUserActions.loadUsers({scope: 2}));

        expect(tenantUserReducer(state, TenantUserActions.loadUsersSuccess({scope: 1, users: [user(1, "a")]}))).toBe(
            state,
        );
        expect(tenantUserReducer(state, TenantUserActions.createUserSuccess({scope: 1, user: user(2, "b")}))).toBe(
            state,
        );
    });

    it("keeps the users sorted by username", () => {
        let state = tenantUserReducer(initialState, TenantUserActions.loadUsers({scope: 1}));
        state = tenantUserReducer(
            state,
            TenantUserActions.loadUsersSuccess({scope: 1, users: [user(1, "max"), user(2, "anna")]}),
        );
        state = tenantUserReducer(state, TenantUserActions.createUserSuccess({scope: 1, user: user(3, "lea")}));

        expect(state.users.map(u => u.username)).toEqual(["anna", "lea", "max"]);
        expect(state.loading).toBe(false);
    });

    it("shows a tenant admin's own choice in the menu on its row - the dialog then sends the new one", () => {
        const loaded = tenantUserReducer(
            tenantUserReducer(initialState, TenantUserActions.loadUsers({scope: "self"})),
            TenantUserActions.loadUsersSuccess({scope: "self", users: [user(1, "anna"), user(2, "bert")]}),
        );

        const state = tenantUserReducer(loaded, LanguageActions.chooseLanguageSuccess({userId: 1, language: "de"}));

        expect(state.users.map(u => u.language)).toEqual(["de", "en"]);
        expect(
            tenantUserReducer(loaded, LanguageActions.chooseLanguageSuccess({userId: null, language: "de"})),
        ).toEqual(loaded);
    });
});
