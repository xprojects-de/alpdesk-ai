import {TestBed} from "@angular/core/testing";
import {Router} from "@angular/router";
import {MatDialog} from "@angular/material/dialog";
import {provideMockActions} from "@ngrx/effects/testing";
import {HttpErrorResponse} from "@angular/common/http";
import {Action} from "@ngrx/store";
import {of, Subject, throwError} from "rxjs";
import {vi} from "vitest";
import {AuthService} from "../../services/auth.service";
import * as AuthActions from "./auth.actions";
import {AuthEffects} from "./auth.effects";
import {translocoTesting} from "../../i18n/i18n.testing";
import {LAST_TENANT_KEY} from "../../utils/last-tenant.util";

describe("AuthEffects", () => {
    function setup(authService: Partial<AuthService> = {}) {
        const actions$ = new Subject<Action>();
        const dialog = {closeAll: vi.fn()};
        const router = {navigate: vi.fn(() => Promise.resolve(true))};
        TestBed.configureTestingModule({
            imports: [translocoTesting("de")],
            providers: [
                AuthEffects,
                provideMockActions(() => actions$),
                {provide: MatDialog, useValue: dialog},
                {provide: Router, useValue: router},
                {provide: AuthService, useValue: authService},
            ],
        });
        const effects = TestBed.inject(AuthEffects);
        return {actions$, dialog, router, effects};
    }

    it("closes every open dialog on logout before going to the login page", () => {
        const {actions$, dialog, router, effects} = setup();
        effects.logoutSuccess$.subscribe();

        actions$.next(AuthActions.logoutSuccess());

        expect(dialog.closeAll).toHaveBeenCalledTimes(1);
        expect(router.navigate).toHaveBeenCalledWith(["/login"]);
    });

    it("starts this tab anew when another tab changes the session - its requests would carry the other token", () => {
        const changed = new Subject<void>();
        const {effects} = setup({sessionChangedInOtherTab: () => changed});
        const reload = vi
            .spyOn(effects as unknown as {reloadPage: () => void}, "reloadPage")
            .mockReturnValue(undefined);
        effects.otherTabSession$.subscribe();

        changed.next();

        expect(reload).toHaveBeenCalledTimes(1);
    });

    describe("sign-in through the WAM proxy", () => {
        function wamService(answer: ReturnType<AuthService["wamLogin"]>) {
            return {
                wamLogin: vi.fn(() => answer),
                extractToken: (response: {access_token?: string}) => response.access_token ?? null,
                clearSignedOut: vi.fn(),
                logout: vi.fn(),
            };
        }

        function signIn(authService: ReturnType<typeof wamService>, tenantId?: number): Action[] {
            const {actions$, effects} = setup(authService as unknown as Partial<AuthService>);
            const emitted: Action[] = [];
            effects.wamSignIn$.subscribe(action => emitted.push(action));
            actions$.next(AuthActions.wamSignIn(tenantId === undefined ? {} : {tenantId}));
            return emitted;
        }

        beforeEach(() => localStorage.removeItem(LAST_TENANT_KEY));

        it("lets a person with users in several tenants choose - no token yet", () => {
            const tenants = [
                {id: 1, name: "Thema A", loginKey: "a"},
                {id: 2, name: "Allgemein", loginKey: "b"},
            ];

            expect(signIn(wamService(of({tenants})))).toEqual([AuthActions.wamChoose({tenants})]);
        });

        it("signs in at the chosen tenant and remembers it for the next visit", () => {
            const response = {access_token: "jwt", username: "anna", roles: ["ROLE_TENANT_USER"]};
            const service = wamService(of(response));

            expect(signIn(service, 2)).toEqual([AuthActions.loginSuccess({response})]);
            expect(service.wamLogin).toHaveBeenCalledWith(2);
            expect(localStorage.getItem(LAST_TENANT_KEY)).toBe("2");
        });

        it("tries the tenant chosen last in this browser first - a second visit needs no click", () => {
            localStorage.setItem(LAST_TENANT_KEY, "2");
            const response = {access_token: "jwt", username: "anna", roles: ["ROLE_TENANT_USER"]};
            const service = wamService(of(response));

            expect(signIn(service)).toEqual([AuthActions.loginSuccess({response})]);
            expect(service.wamLogin).toHaveBeenCalledWith(2);
        });

        it("forgets a remembered tenant where the person has no user any more (404) and signs in without it", () => {
            localStorage.setItem(LAST_TENANT_KEY, "7");
            const response = {access_token: "jwt", username: "anna", roles: ["ROLE_TENANT_USER"]};
            const service = wamService(of(response));
            service.wamLogin = vi.fn((tenantId?: number) =>
                tenantId === 7 ? throwError(() => new HttpErrorResponse({status: 404})) : of(response),
            );

            expect(signIn(service)).toEqual([AuthActions.loginSuccess({response})]);
            expect(service.wamLogin).toHaveBeenCalledTimes(2);
            expect(localStorage.getItem(LAST_TENANT_KEY)).toBeNull();
        });

        it("logs a known user in like the password login, and forgets an earlier logout of the tab", () => {
            const response = {access_token: "jwt", username: "anna", roles: ["ROLE_TENANT_USER"]};
            const service = wamService(of(response));

            expect(signIn(service)).toEqual([AuthActions.loginSuccess({response})]);
            expect(service.clearSignedOut).toHaveBeenCalled();
        });

        it("tells someone waiting for approval (409) apart from a failure", () => {
            expect(signIn(wamService(throwError(() => new HttpErrorResponse({status: 409}))))).toEqual([
                AuthActions.wamWaiting(),
            ]);
        });

        it("tells a person in no group so - it does not wait for an approval that never comes", () => {
            const [action] = signIn(
                wamService(
                    throwError(
                        () =>
                            new HttpErrorResponse({
                                status: 409,
                                error: {code: "user.noGroup", message: "You are in no group of this company"},
                            }),
                    ),
                ),
            );

            expect(action).toEqual(
                AuthActions.wamSignInFailure({
                    error: "Sie sind keiner Gruppe dieser Firma zugeordnet - bitten Sie deren Administrator, Sie einer zuzuordnen",
                }),
            );
        });

        it("says in the user's language when the proxy sent no usable user (400) - not the backend's words", () => {
            const [action] = signIn(
                wamService(
                    throwError(
                        () => new HttpErrorResponse({status: 400, error: {message: "The WAM proxy sent no user"}}),
                    ),
                ),
            );

            expect(action).toEqual(
                AuthActions.wamSignInFailure({
                    error: "Der WAM-Proxy hat keinen gültigen Benutzer übermittelt - bitte die Anwendung über ihn aufrufen",
                }),
            );
        });

        it("refuses a token without an area to go to (e.g. no tenant role) and keeps nothing of it", () => {
            const service = wamService(of({access_token: "jwt", username: "x", roles: []}));

            expect(signIn(service)[0].type).toBe(AuthActions.wamSignInFailure.type);
            expect(service.logout).toHaveBeenCalled();
        });
    });

    describe("the switch to another tenant", () => {
        const tenant = {id: 5, name: "Allgemein", loginKey: "k5"};

        function switchTo(answer: ReturnType<AuthService["switchTenant"]>, password?: string) {
            const authService = {
                switchTenant: vi.fn(() => answer),
                extractToken: (response: {access_token?: string}) => response.access_token ?? null,
            };
            const {actions$, effects} = setup(authService as unknown as Partial<AuthService>);
            const emitted: Action[] = [];
            effects.switchTenant$.subscribe(action => emitted.push(action));
            actions$.next(AuthActions.switchTenant({tenant, password}));
            return {emitted, authService};
        }

        beforeEach(() => localStorage.removeItem(LAST_TENANT_KEY));

        it("takes the new token, with the target's password where one is asked, and remembers the tenant", () => {
            const response = {access_token: "jwt-5", username: "anna", roles: ["ROLE_TENANT_USER"]};
            const {emitted, authService} = switchTo(of(response), "geheim-genug-123");

            expect(emitted).toEqual([AuthActions.switchTenantSuccess({response})]);
            expect(authService.switchTenant).toHaveBeenCalledWith(5, "geheim-genug-123");
            expect(localStorage.getItem(LAST_TENANT_KEY)).toBe("5");
        });

        it("reports a refused switch and stays where it is", () => {
            const {emitted} = switchTo(
                throwError(() => new HttpErrorResponse({status: 400, error: {code: "account.switchPasswordWrong"}})),
            );

            expect(emitted.map(action => action.type)).toEqual([AuthActions.switchTenantFailure.type]);
            expect(localStorage.getItem(LAST_TENANT_KEY)).toBeNull();
        });
    });

    describe("logout", () => {
        function logout(byUser: boolean | undefined) {
            const authService = {logout: vi.fn(), markSignedOut: vi.fn()};
            const {actions$, effects} = setup(authService as unknown as Partial<AuthService>);
            effects.logout$.subscribe();
            actions$.next(byUser === undefined ? AuthActions.logout() : AuthActions.logout({byUser}));
            return authService;
        }

        it("marks the tab only when the user logged out - with WAM the login page then waits", () => {
            const byUser = logout(true);
            expect(byUser.logout).toHaveBeenCalled();
            expect(byUser.markSignedOut).toHaveBeenCalled();
        });

        it("does not mark it on a forced logout (another person at the proxy, an expired token) - they sign in again", () => {
            const forced = logout(undefined);
            expect(forced.logout).toHaveBeenCalled();
            expect(forced.markSignedOut).not.toHaveBeenCalled();
        });
    });
});
