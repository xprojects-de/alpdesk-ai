import {DOCUMENT, inject, Injectable} from "@angular/core";
import {MatSnackBar} from "@angular/material/snack-bar";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {Store} from "@ngrx/store";
import {TranslocoService} from "@jsverse/transloco";
import {of} from "rxjs";
import {catchError, concatMap, filter, map, switchMap, tap, withLatestFrom} from "rxjs/operators";
import {ROLE_TENANT_USER} from "../../models/auth.model";
import {AccountService} from "../../services/account.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import {applyLanguage, browserLanguage, languageOf} from "../../utils/language.util";
import * as AccountActions from "../account/account.actions";
import * as AuthActions from "../auth/auth.actions";
import * as AuthSelectors from "../auth/auth.selectors";
import * as AccountSelectors from "../account/account.selectors";
import * as TenantUserActions from "../tenant-user/tenant-user.actions";
import * as LanguageActions from "./language.actions";

@Injectable()
export class LanguageEffects {
    private actions$ = inject(Actions);
    private store = inject(Store);
    private document = inject(DOCUMENT);
    private transloco = inject(TranslocoService);
    private accountService = inject(AccountService);
    private errors = inject(ErrorMessages);
    private snackBar = inject(MatSnackBar);

    /**
     * Every page re-renders in the language - once its file is loaded, so no text written in code meanwhile comes out
     * as a key. Nothing of it is kept in the browser.
     */
    speak$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(LanguageActions.useLanguage, LanguageActions.chooseLanguage),
                map(({language}) => languageOf(language)),
                switchMap(language => this.transloco.load(language).pipe(map(() => language))),
                tap(language => {
                    this.transloco.setActiveLang(language);
                    applyLanguage(this.document.documentElement, language);
                }),
            ),
        {dispatch: false},
    );

    /** After a logout the login page speaks the browser's language again, not the last user's. */
    afterLogout$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AuthActions.logoutSuccess),
            map(() =>
                LanguageActions.useLanguage({
                    language: browserLanguage(this.document.defaultView?.navigator.languages ?? []),
                }),
            ),
        ),
    );

    /** A tenant user's account carries its language - it wins over the browser's. */
    fromAccount$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AccountActions.loadAccountSuccess),
            filter(({account}) => languageOf(account.language) !== this.transloco.getActiveLang()),
            map(({account}) => LanguageActions.useLanguage({language: account.language})),
        ),
    );

    /**
     * A tenant admin who changed its own language in the user dialog: the page follows at once, as with the menu.
     * (The admin edits other people only - it has no account.)
     */
    fromOwnUserUpdate$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TenantUserActions.updateUserSuccess),
            withLatestFrom(this.store.select(AccountSelectors.selectAccount)),
            filter(
                ([{user}, account]) =>
                    user.id === account?.userId && languageOf(user.language) !== this.transloco.getActiveLang(),
            ),
            map(([{user}]) => LanguageActions.useLanguage({language: user.language})),
        ),
    );

    /** The admin has no account row - its choice holds until the page is reloaded. */
    save$ = createEffect(() =>
        this.actions$.pipe(
            ofType(LanguageActions.chooseLanguage),
            withLatestFrom(
                this.store.select(AuthSelectors.selectAuthRoles),
                this.store.select(AccountSelectors.selectAccount),
            ),
            // After the language's file: a failure is then said in the chosen language, not as a key.
            concatMap(([{language}, roles, account]) =>
                this.transloco
                    .load(languageOf(language))
                    .pipe(map(() => ({language: languageOf(language), roles, account}))),
            ),
            concatMap(({language, roles, account}) =>
                !roles.includes(ROLE_TENANT_USER)
                    ? of(LanguageActions.chooseLanguageSuccess({userId: null, language}))
                    : this.accountService.changeLanguage(language).pipe(
                          map(() => LanguageActions.chooseLanguageSuccess({userId: account?.userId ?? null, language})),
                          catchError(error =>
                              of(
                                  LanguageActions.chooseLanguageFailure({
                                      error: this.errors.of(error, "errors.language.save"),
                                  }),
                              ),
                          ),
                      ),
            ),
        ),
    );

    /** The page already speaks the chosen language - only the account did not keep it for the next login. */
    saveFailure$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(LanguageActions.chooseLanguageFailure),
                tap(({error}) =>
                    this.snackBar.open(error, this.transloco.translate("common.ok"), {
                        duration: 10000,
                        panelClass: "error-snackbar",
                    }),
                ),
            ),
        {dispatch: false},
    );
}
