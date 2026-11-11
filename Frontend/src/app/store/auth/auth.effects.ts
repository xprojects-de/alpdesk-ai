import {inject, Injectable} from "@angular/core";
import {HttpErrorResponse} from "@angular/common/http";
import {ErrorMessages} from "../../i18n/error-messages.service";
import {TranslocoService} from "@jsverse/transloco";
import {Router} from "@angular/router";
import {MatDialog} from "@angular/material/dialog";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {defer, Observable, of} from "rxjs";
import {catchError, exhaustMap, map, mergeMap, switchMap, tap} from "rxjs/operators";
import {AuthService} from "../../services/auth.service";
import * as AuthActions from "./auth.actions";
import {homePathFor} from "../../utils/auth.util";
import {localStorageOf} from "../../utils/color-scheme.util";
import {loginPath, readLoginKey, storeLoginKey} from "../../utils/login-page.util";
import {readLastTenant, storeLastTenant} from "../../utils/last-tenant.util";
import {LoginResponse, TenantChoice} from "../../models/auth.model";

@Injectable()
export class AuthEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private transloco = inject(TranslocoService);
    private authService = inject(AuthService);
    private router = inject(Router);
    private dialog = inject(MatDialog);

    login$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AuthActions.login),
            mergeMap(({credentials}) =>
                this.authService.login(credentials).pipe(
                    map(response => {
                        // Store username in response
                        const enhancedResponse = {...response, username: credentials.username};
                        if (!this.authService.extractToken(enhancedResponse)) {
                            // A 2xx response with no token would otherwise still be treated as a
                            // successful login (isAuthenticated: true with a null token), leaving
                            // every subsequent request unauthenticated with no explanation.
                            throw new Error("Login response did not include an authentication token");
                        }
                        if (!homePathFor(response.roles ?? [])) {
                            // Logged in, but with no area to go to - the login page would simply stay open.
                            this.authService.logout();
                            throw new Error(this.transloco.translate("errors.auth.noArea"));
                        }
                        return AuthActions.loginSuccess({response: enhancedResponse});
                    }),
                    catchError(error =>
                        of(
                            AuthActions.loginFailure({
                                // A rejected login answers 401 without a body of the backend's - its own text here.
                                // On /login (no tenant's key) the hint for a name in several tenants - after any
                                // refused attempt alike, so the answer tells nothing of which usernames exist.
                                error:
                                    error instanceof HttpErrorResponse && error.status === 401
                                        ? this.transloco.translate(
                                              credentials.loginKey
                                                  ? "errors.auth.wrongCredentials"
                                                  : "errors.auth.wrongCredentialsNeutral",
                                          )
                                        : this.errors.of(error, "errors.auth.login"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    // No loginSuccess$ navigation effect: LoginComponent already redirects to the user's area whenever
    // selectHomePath gets a value (which a successful login causes), and a second navigate
    // call here duplicated that on every login.

    loadWamStatus$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AuthActions.loadWamStatus),
            switchMap(() =>
                this.authService.wamStatus().pipe(
                    map(({enabled, groupsChecked}) => AuthActions.loadWamStatusSuccess({enabled, groupsChecked})),
                    // Unknown (backend unreachable): the password login stays - the health banner says the rest.
                    catchError(() => of(AuthActions.loadWamStatusSuccess({enabled: false, groupsChecked: false}))),
                ),
            ),
        ),
    );

    wamSignIn$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AuthActions.wamSignIn),
            tap(() => this.authService.clearSignedOut()),
            exhaustMap(({tenantId}) =>
                this.wamLogin(tenantId).pipe(
                    map(response => {
                        if (response.tenants && !this.authService.extractToken(response)) {
                            return AuthActions.wamChoose({tenants: response.tenants});
                        }
                        if (!this.authService.extractToken(response) || !homePathFor(response.roles ?? [])) {
                            this.authService.logout();
                            throw new Error(this.transloco.translate("errors.auth.noArea"));
                        }
                        if (tenantId !== undefined) {
                            storeLastTenant(localStorageOf(window), tenantId);
                        }
                        return AuthActions.loginSuccess({response});
                    }),
                    catchError(error => of(this.wamFailureAction(error))),
                ),
            ),
        ),
    );

    switchTenant$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AuthActions.switchTenant),
            exhaustMap(({tenant, password}) =>
                this.authService.switchTenant(tenant.id, password).pipe(
                    map(response => {
                        if (!this.authService.extractToken(response) || !homePathFor(response.roles ?? [])) {
                            throw new Error(this.transloco.translate("errors.auth.noArea"));
                        }
                        this.remember(tenant);
                        return AuthActions.switchTenantSuccess({response});
                    }),
                    catchError(error =>
                        of(AuthActions.switchTenantFailure({error: this.errors.of(error, "errors.auth.switchTenant")})),
                    ),
                ),
            ),
        ),
    );

    /**
     * After the switch the store is reset (logout.meta-reducer.ts) - the tenant area starts anew with the new token,
     * every page loading the new tenant's data: a fresh start of the app at /app does exactly that.
     */
    switchTenantSuccess$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(AuthActions.switchTenantSuccess),
                tap(() => {
                    this.dialog.closeAll();
                    window.location.assign(new URL("app", document.baseURI).href);
                }),
            ),
        {dispatch: false},
    );

    /**
     * The token is shared by every tab: after a switch, login or logout in another tab this one would send its
     * uploads, deletions and questions with the other session's token - into another tenant. It starts anew instead.
     */
    otherTabSession$ = createEffect(
        () => defer(() => this.authService.sessionChangedInOtherTab()).pipe(tap(() => this.reloadPage())),
        {dispatch: false},
    );

    logout$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AuthActions.logout),
            tap(({byUser}) => {
                this.authService.logout();
                if (byUser) {
                    this.authService.markSignedOut();
                }
            }),
            map(() => AuthActions.logoutSuccess()),
        ),
    );

    logoutSuccess$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(AuthActions.logoutSuccess),
                tap(() => {
                    // A dialog open at a forced logout (expired token, 401) would stay on top of the login page -
                    // with its data, e.g. a freshly created API key - and save into the reset store after the next
                    // login.
                    this.dialog.closeAll();
                    this.router.navigate(loginPath(readLoginKey(localStorageOf(window)))).then();
                }),
            ),
        {dispatch: false},
    );

    checkAuth$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AuthActions.checkAuth),
            map(() => {
                const token = this.authService.getToken();
                const username = this.authService.getUsername();
                const roles = this.authService.getRoles();

                if (token && username && homePathFor(roles)) {
                    if (this.authService.isTokenExpired(token)) {
                        this.authService.logout();
                        return AuthActions.checkAuthFailure();
                    }
                    return AuthActions.checkAuthSuccess({token, username, roles});
                } else {
                    return AuthActions.checkAuthFailure();
                }
            }),
        ),
    );

    /**
     * With a tenant chosen before in this browser it is tried first; a tenant where the person has no user any more
     * (404) is forgotten, and the sign-in goes on without it.
     */
    private wamLogin(chosen: number | undefined): Observable<LoginResponse> {
        const stored = chosen === undefined ? readLastTenant(localStorageOf(window)) : undefined;
        if (stored === undefined) {
            return this.authService.wamLogin(chosen);
        }
        return this.authService.wamLogin(stored).pipe(
            catchError(error => {
                if (error instanceof HttpErrorResponse && error.status === 404) {
                    storeLastTenant(localStorageOf(window), null);
                    return this.authService.wamLogin();
                }
                throw error;
            }),
        );
    }

    /** The switch updates the last choice, and the remembered login page if there is one, to the new tenant. */
    private remember(tenant: TenantChoice): void {
        const storage = localStorageOf(window);
        storeLastTenant(storage, tenant.id);
        if (readLoginKey(storage) && tenant.loginKey) {
            storeLoginKey(storage, tenant.loginKey);
        }
    }

    /**
     * 409: waiting for approval - or, with the code user.noGroup, in no group (said so, nothing to wait for); 400: the
     * proxy sent no usable user - said in the user's words, not the backend's.
     */
    private wamFailureAction(error: unknown) {
        if (error instanceof HttpErrorResponse && error.status === 409 && error.error?.code !== "user.noGroup") {
            return AuthActions.wamWaiting();
        }
        if (error instanceof HttpErrorResponse && error.status === 400) {
            return AuthActions.wamSignInFailure({error: this.transloco.translate("errors.auth.wamNoUser")});
        }
        return AuthActions.wamSignInFailure({error: this.errors.of(error, "errors.auth.wamSignIn")});
    }

    protected reloadPage(): void {
        window.location.reload();
    }
}
