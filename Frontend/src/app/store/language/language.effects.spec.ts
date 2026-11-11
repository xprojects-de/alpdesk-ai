import {TestBed} from "@angular/core/testing";
import {HttpErrorResponse} from "@angular/common/http";
import {MatSnackBar} from "@angular/material/snack-bar";
import {provideMockActions} from "@ngrx/effects/testing";
import {Action} from "@ngrx/store";
import {provideMockStore} from "@ngrx/store/testing";
import {TranslocoService} from "@jsverse/transloco";
import {of, Subject, throwError} from "rxjs";
import {vi} from "vitest";
import {ROLE_ADMIN, ROLE_TENANT_USER} from "../../models/auth.model";
import {Account} from "../../models/account.model";
import {AccountService} from "../../services/account.service";
import {translocoTesting} from "../../i18n/i18n.testing";
import {initialState as authInitialState} from "../auth/auth.reducer";
import * as AccountActions from "../account/account.actions";
import * as AuthActions from "../auth/auth.actions";
import * as LanguageActions from "./language.actions";
import * as TenantUserActions from "../tenant-user/tenant-user.actions";
import {LanguageEffects} from "./language.effects";

/** The signed-in tenant user's account (user 1). */
function account(language: string): Account {
    return {
        userId: 1,
        username: "anna",
        displayName: "Anna",
        tenantId: 2,
        tenantName: "Musterfirma",
        admin: true,
        language,
        mcpServer: false,
        tasksAllowed: false,
        answersThroughAgent: false,
        tenants: [],
    };
}

describe("LanguageEffects", () => {
    function setup(roles: string[], changeLanguage = vi.fn(() => of(undefined))) {
        const actions$ = new Subject<Action>();
        TestBed.configureTestingModule({
            imports: [translocoTesting("en")],
            providers: [
                LanguageEffects,
                provideMockActions(() => actions$),
                provideMockStore({
                    initialState: {
                        auth: {...authInitialState, roles},
                        account: {account: roles.includes(ROLE_TENANT_USER) ? account("en") : null},
                    },
                }),
                {provide: AccountService, useValue: {changeLanguage}},
                {provide: MatSnackBar, useValue: {open: vi.fn()}},
            ],
        });
        return {actions$, effects: TestBed.inject(LanguageEffects), changeLanguage};
    }

    function emitted(source: {subscribe: (next: (action: Action) => void) => unknown}): Action[] {
        const actions: Action[] = [];
        source.subscribe(action => actions.push(action));
        return actions;
    }

    it("speaks the language at once - Transloco and the page's lang - and keeps nothing of it in the browser", () => {
        const {actions$, effects} = setup([]);
        effects.speak$.subscribe();

        actions$.next(LanguageActions.chooseLanguage({language: "de-AT"}));

        expect(TestBed.inject(TranslocoService).getActiveLang()).toBe("de");
        expect(document.documentElement.lang).toBe("de");
        expect(localStorage.length).toBe(0);
    });

    it("speaks the browser's language again after a logout - not the last user's", () => {
        const {actions$, effects} = setup([ROLE_TENANT_USER]);
        const actions = emitted(effects.afterLogout$);
        vi.spyOn(navigator, "languages", "get").mockReturnValue(["de-CH", "en"]);

        actions$.next(AuthActions.logoutSuccess());

        expect(actions).toEqual([LanguageActions.useLanguage({language: "de"})]);
    });

    it("saves a tenant user's choice to its account", () => {
        const {actions$, effects, changeLanguage} = setup([ROLE_TENANT_USER]);
        const actions = emitted(effects.save$);

        actions$.next(LanguageActions.chooseLanguage({language: "de"}));

        expect(changeLanguage).toHaveBeenCalledWith("de");
        expect(actions).toEqual([LanguageActions.chooseLanguageSuccess({userId: 1, language: "de"})]);
    });

    it("keeps the admin's choice in the browser only - it has no account", () => {
        const {actions$, effects, changeLanguage} = setup([ROLE_ADMIN]);
        const actions = emitted(effects.save$);

        actions$.next(LanguageActions.chooseLanguage({language: "de"}));

        expect(changeLanguage).not.toHaveBeenCalled();
        expect(actions).toEqual([LanguageActions.chooseLanguageSuccess({userId: null, language: "de"})]);
    });

    it("says so when the account did not keep the choice", () => {
        const failing = vi.fn(() => throwError(() => new HttpErrorResponse({status: 500, error: null})));
        const {actions$, effects} = setup([ROLE_TENANT_USER], failing);
        const actions = emitted(effects.save$);

        actions$.next(LanguageActions.chooseLanguage({language: "de"}));

        expect(actions).toEqual([
            LanguageActions.chooseLanguageFailure({error: "The language could not be saved to your account"}),
        ]);
    });

    it("takes the account's language after the login - only when it differs from the one spoken", () => {
        const {actions$, effects} = setup([ROLE_TENANT_USER]);
        const actions = emitted(effects.fromAccount$);

        actions$.next(AccountActions.loadAccountSuccess({account: account("en")}));
        actions$.next(AccountActions.loadAccountSuccess({account: account("de")}));

        expect(actions).toEqual([LanguageActions.useLanguage({language: "de"})]);
    });

    it("follows a tenant admin's own language changed in the user dialog - not a colleague's", () => {
        const {actions$, effects} = setup([ROLE_TENANT_USER]);
        const actions = emitted(effects.fromOwnUserUpdate$);
        const user = (id: number, language: string) => ({
            id,
            username: "u" + id,
            displayName: "U",
            active: true,
            admin: false,
            createdAt: "2026-01-01T00:00:00Z",
            updatedAt: "2026-01-01T00:00:00Z",
            lastLoginAt: null,
            groupIds: [],
            wamGroups: [],
            language,
        });

        actions$.next(TenantUserActions.updateUserSuccess({scope: "self", user: user(7, "de")}));
        actions$.next(TenantUserActions.updateUserSuccess({scope: "self", user: user(1, "de")}));

        expect(actions).toEqual([LanguageActions.useLanguage({language: "de"})]);
    });
});
