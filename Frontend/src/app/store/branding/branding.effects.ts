import {DOCUMENT, inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {Store} from "@ngrx/store";
import {combineLatest, Observable, of, Subscription} from "rxjs";
import {catchError, concatMap, map, switchMap, tap} from "rxjs/operators";
import {Branding, NEUTRAL_BRANDING, NEUTRAL_TITLE_KEY} from "../../models/branding.model";
import {TranslocoService} from "@jsverse/transloco";
import {BrandingService} from "../../services/branding.service";
import {themeVariables} from "../../utils/branding.util";
import {ErrorMessages} from "../../i18n/error-messages.service";
import {HttpErrorResponse} from "@angular/common/http";
import {localStorageOf} from "../../utils/color-scheme.util";
import {storeLoginKey} from "../../utils/login-page.util";
import * as AuthActions from "../auth/auth.actions";
import {endedByLogout} from "../session.operator";
import * as BrandingActions from "./branding.actions";
import * as BrandingSelectors from "./branding.selectors";

@Injectable()
export class BrandingEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private store = inject(Store);
    private brandingService = inject(BrandingService);
    private document = inject(DOCUMENT);
    private transloco = inject(TranslocoService);

    /** Object URLs of logo blobs - revoked when replaced, so the browser frees them. */
    private tenantLogoUrl: string | null = null;
    private adminLogoUrl: string | null = null;
    /** The colour variables set on <html> - removed before the next look's are set. */
    private appliedVariables: string[] = [];
    /** The login page's title follows the language - also when its file is still loading at start. */
    private neutralTitle?: Subscription;

    loadInstallation$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.loadInstallationBranding),
            switchMap(() =>
                this.brandingService.getInstallation().pipe(
                    map(branding =>
                        BrandingActions.loadInstallationBrandingSuccess({
                            branding,
                            logoUrl: branding.hasLogo ? this.brandingService.installationLogoUrl : null,
                        }),
                    ),
                    // Not a page's failure: the build's own look stays (and the offline banner tells the rest).
                    catchError(error =>
                        of(
                            BrandingActions.loadInstallationBrandingFailure({
                                error: this.errors.of(error, "errors.branding.loadInstallationBranding"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    /** The page stays neutral when the look cannot be loaded; a key nobody has any more is forgotten. */
    loadLogin$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.loadLoginBranding),
            switchMap(({loginKey}) =>
                this.brandingService.getLogin(loginKey).pipe(
                    map(branding =>
                        BrandingActions.loadLoginBrandingSuccess({
                            loginKey,
                            branding,
                            logoUrl: branding.hasLogo ? this.brandingService.loginLogoUrl(loginKey) : null,
                        }),
                    ),
                    // Not in the load-failure snackbar: an outdated link shows the neutral page, nothing more.
                    catchError(error =>
                        of(
                            BrandingActions.loadLoginBrandingFailure({
                                error: this.errors.of(error, "errors.branding.loadLoginBranding"),
                                unknownKey: error instanceof HttpErrorResponse && error.status === 404,
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    rememberLoginKey$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(BrandingActions.loadLoginBrandingSuccess, BrandingActions.loadLoginBrandingFailure),
                tap(action => {
                    if (action.type === BrandingActions.loadLoginBrandingSuccess.type) {
                        storeLoginKey(localStorageOf(this.document.defaultView), action.loginKey);
                    } else if (action.unknownKey) {
                        storeLoginKey(localStorageOf(this.document.defaultView), null);
                    }
                }),
            ),
        {dispatch: false},
    );

    loadOwnLoginKey$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.loadOwnLoginKey),
            switchMap(() =>
                this.brandingService.getOwnLoginKey().pipe(
                    map(({loginKey}) => BrandingActions.loadOwnLoginKeySuccess({loginKey})),
                    catchError(error =>
                        of(
                            BrandingActions.loadOwnLoginKeyFailure({
                                error: this.errors.of(error, "errors.branding.loadOwnLoginKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    loadTenant$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.loadTenantBranding),
            switchMap(() =>
                this.brandingService.getTenant().pipe(
                    switchMap(branding =>
                        this.logoUrl(branding.hasLogo, () => this.brandingService.getTenantLogo()).pipe(
                            map(logoUrl => {
                                this.tenantLogoUrl = this.replace(this.tenantLogoUrl, logoUrl);
                                return BrandingActions.loadTenantBrandingSuccess({branding, logoUrl});
                            }),
                        ),
                    ),
                    catchError(error =>
                        of(
                            BrandingActions.loadTenantBrandingFailure({
                                error: this.errors.of(error, "errors.branding.loadTenantBranding"),
                            }),
                        ),
                    ),
                ),
            ),
            // The slice survives a logout: a look still loading must not be restored for the next login.
            endedByLogout(this.actions$),
        ),
    );

    releaseTenantLogo$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(AuthActions.logoutSuccess),
                tap(() => (this.tenantLogoUrl = this.replace(this.tenantLogoUrl, null))),
            ),
        {dispatch: false},
    );

    /** Whatever look is shown - the installation's or the tenant's - goes into the page: colours, title, favicon. */
    apply$ = createEffect(
        () =>
            combineLatest([
                this.store.select(BrandingSelectors.selectBranding),
                this.store.select(BrandingSelectors.selectLogoUrl),
            ]).pipe(tap(([branding, logoUrl]) => this.apply(branding, logoUrl))),
        {dispatch: false},
    );

    loadAdmin$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.loadAdminBranding),
            switchMap(({tenantId}) =>
                this.brandingService.getForTenant(tenantId).pipe(
                    switchMap(branding =>
                        this.logoUrl(branding.hasLogo, () => this.brandingService.getLogoOfTenant(tenantId)).pipe(
                            map(logoUrl => {
                                this.adminLogoUrl = this.replace(this.adminLogoUrl, logoUrl);
                                return BrandingActions.loadAdminBrandingSuccess({tenantId, branding, logoUrl});
                            }),
                        ),
                    ),
                    catchError(error =>
                        of(
                            BrandingActions.loadAdminBrandingFailure({
                                error: this.errors.of(error, "errors.branding.loadAdminBranding"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    saveAdmin$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.saveAdminBranding),
            concatMap(({tenantId, request}) =>
                this.brandingService.saveForTenant(tenantId, request).pipe(
                    map(branding => BrandingActions.saveAdminBrandingSuccess({tenantId, branding})),
                    catchError(error =>
                        of(
                            BrandingActions.saveAdminBrandingFailure({
                                error: this.errors.of(error, "errors.branding.saveAdminBranding"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    renewAdminLoginKey$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.renewAdminLoginKey),
            concatMap(({tenantId}) =>
                this.brandingService.renewLoginKeyOfTenant(tenantId).pipe(
                    map(branding => BrandingActions.renewAdminLoginKeySuccess({tenantId, branding})),
                    catchError(error =>
                        of(
                            BrandingActions.renewAdminLoginKeyFailure({
                                error: this.errors.of(error, "errors.branding.renewAdminLoginKey"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    uploadAdminLogo$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.uploadAdminLogo),
            concatMap(({tenantId, file}) =>
                this.brandingService.uploadLogoOfTenant(tenantId, file).pipe(
                    map(() => BrandingActions.uploadAdminLogoSuccess({tenantId})),
                    catchError(error =>
                        of(
                            BrandingActions.uploadAdminLogoFailure({
                                error: this.errors.of(error, "errors.branding.uploadAdminLogo"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    deleteAdminLogo$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.deleteAdminLogo),
            concatMap(({tenantId}) =>
                this.brandingService.deleteLogoOfTenant(tenantId).pipe(
                    map(() => BrandingActions.deleteAdminLogoSuccess({tenantId})),
                    catchError(error =>
                        of(
                            BrandingActions.deleteAdminLogoFailure({
                                error: this.errors.of(error, "errors.branding.deleteAdminLogo"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    /** After a logo changed the dialog shows it - loaded again with the rest. */
    reloadAdminAfterLogo$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BrandingActions.uploadAdminLogoSuccess, BrandingActions.deleteAdminLogoSuccess),
            map(({tenantId}) => BrandingActions.loadAdminBranding({tenantId})),
        ),
    );

    /** An object URL of the logo, or null without one or when it cannot be loaded (the look stays without logo). */
    private logoUrl(hasLogo: boolean, load: () => Observable<Blob>): Observable<string | null> {
        if (!hasLogo) {
            return of(null);
        }
        return load().pipe(
            map(blob => URL.createObjectURL(blob)),
            catchError(() => of(null)),
        );
    }

    private replace(previous: string | null, next: string | null): string | null {
        if (previous && previous !== next) {
            URL.revokeObjectURL(previous);
        }
        return next;
    }

    private apply(branding: Branding, logoUrl: string | null): void {
        const root = this.document.documentElement;
        this.appliedVariables.forEach(name => root.style.removeProperty(name));
        const variables = themeVariables(branding.primaryColor);
        Object.entries(variables).forEach(([name, value]) => root.style.setProperty(name, value));
        this.appliedVariables = Object.keys(variables);
        // Before the login the title is the page's purpose (a key), afterwards the brand's name.
        this.neutralTitle?.unsubscribe();
        if (branding === NEUTRAL_BRANDING) {
            this.neutralTitle = this.transloco
                .selectTranslate(NEUTRAL_TITLE_KEY)
                .subscribe(title => (this.document.title = title));
        } else {
            this.document.title = branding.name;
        }
        const favicon = this.document.querySelector<HTMLLinkElement>("link[rel='icon']");
        if (favicon) {
            favicon.href = logoUrl ?? "favicon.ico";
        }
    }
}
