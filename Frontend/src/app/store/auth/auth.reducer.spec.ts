import * as AuthActions from "./auth.actions";
import {authReducer, initialState} from "./auth.reducer";

describe("authReducer - the two ways in", () => {
    it("keeps the password login's error and the WAM sign-in's apart", () => {
        let state = authReducer(initialState, AuthActions.loadWamStatusSuccess({enabled: true, groupsChecked: true}));
        state = authReducer(state, AuthActions.wamSignIn());
        state = authReducer(state, AuthActions.wamSignInFailure({error: "WAM kaputt"}));
        state = authReducer(state, AuthActions.login({credentials: {username: "alpdesk", password: "x"}}));
        state = authReducer(state, AuthActions.loginFailure({error: "Benutzername oder Passwort falsch"}));

        expect(state.wamError).toBe("WAM kaputt");
        expect(state.error).toBe("Benutzername oder Passwort falsch");
        expect(state.wamEnabled).toBe(true);
        expect(state.wamGroupsChecked).toBe(true);
    });

    it("waits after a 409 and forgets the waiting once signed in", () => {
        let state = authReducer(initialState, AuthActions.wamSignIn());
        expect(state.loading).toBe(true);

        state = authReducer(state, AuthActions.wamWaiting());
        expect(state.wamWaiting).toBe(true);
        expect(state.loading).toBe(false);

        state = authReducer(state, AuthActions.wamSignIn());
        expect(state.wamWaiting).toBe(false);
        state = authReducer(
            state,
            AuthActions.loginSuccess({response: {access_token: "jwt", username: "anna", roles: ["ROLE_TENANT_USER"]}}),
        );
        expect(state.isAuthenticated).toBe(true);
        expect(state.wamWaiting).toBe(false);
    });

    it("shows the choice of tenants until the person chose one - then signed in, nothing left to choose", () => {
        const tenants = [{id: 1, name: "Thema A", loginKey: "a"}];
        let state = authReducer(initialState, AuthActions.wamSignIn());
        state = authReducer(state, AuthActions.wamChoose({tenants}));
        expect(state.wamChoice).toEqual(tenants);
        expect(state.loading).toBe(false);

        state = authReducer(state, AuthActions.wamSignIn({tenantId: 1}));
        expect(state.wamChoice).toBeNull();
        state = authReducer(
            state,
            AuthActions.loginSuccess({response: {access_token: "jwt", username: "anna", roles: ["ROLE_TENANT_USER"]}}),
        );
        expect(state.isAuthenticated).toBe(true);
    });

    it("keeps a refused switch's error for its dialog and takes the new token after a switch", () => {
        let state = authReducer(initialState, AuthActions.switchTenant({tenant: {id: 2, name: "B", loginKey: "b"}}));
        state = authReducer(state, AuthActions.switchTenantFailure({error: "Passwort falsch"}));
        expect(state.switchError).toBe("Passwort falsch");

        state = authReducer(
            state,
            AuthActions.switchTenantSuccess({
                response: {access_token: "jwt-2", username: "anna", roles: ["ROLE_TENANT_USER"]},
            }),
        );
        expect(state.switchError).toBeNull();
        expect(state.token).toBe("jwt-2");
    });
});
