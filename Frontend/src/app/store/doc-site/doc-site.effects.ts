import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, filter, map, switchMap} from "rxjs/operators";
import {DocSiteService} from "../../services/doc-site.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import {endedByLogout} from "../session.operator";
import {TenantScope} from "../../models/tenant.model";
import * as SiteActions from "./doc-site.actions";

@Injectable()
export class DocSiteEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private siteService = inject(DocSiteService);

    load$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SiteActions.loadSites),
            switchMap(({scope}) =>
                this.siteService.getAll(scope).pipe(
                    map(({sites, maxZipBytes}) => SiteActions.loadSitesSuccess({scope, sites, maxZipBytes})),
                    catchError(error =>
                        of(SiteActions.loadSitesFailure({error: this.errors.of(error, "errors.docSite.loadSites")})),
                    ),
                ),
            ),
        ),
    );

    /** A ZIP of many megabytes outlives the click: a logout ends it, so it never goes out with the next user's token. */
    upload$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SiteActions.uploadSite),
            concatMap(({scope, file, areaId}) =>
                this.siteService.upload(scope, file, areaId).pipe(
                    map(upload => SiteActions.uploadSiteSuccess({scope, upload})),
                    catchError(error =>
                        of(SiteActions.uploadSiteFailure({error: this.errors.of(error, "errors.docSite.uploadSite")})),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    delete$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SiteActions.deleteSite),
            concatMap(({scope, id}) =>
                this.siteService.delete(scope, id).pipe(
                    map(() => SiteActions.deleteSiteSuccess({scope, id})),
                    catchError(error =>
                        of(SiteActions.deleteSiteFailure({error: this.errors.of(error, "errors.docSite.deleteSite")})),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    loadHandbook$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SiteActions.loadHandbook),
            switchMap(() =>
                this.siteService.getReadable().pipe(
                    map(sites => SiteActions.loadHandbookSuccess({sites})),
                    catchError(error =>
                        of(
                            SiteActions.loadHandbookFailure({
                                error: this.errors.of(error, "errors.docSite.loadHandbook"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    /** The handbook stays current after a change of the tenant admin's own websites. */
    reloadHandbook$ = createEffect(() =>
        this.actions$.pipe(
            ofType(SiteActions.uploadSiteSuccess, SiteActions.deleteSiteSuccess),
            filter(({scope}) => scope === "self"),
            map(() => SiteActions.loadHandbook()),
        ),
    );

    /** The handbook's website and a preview apart - one switching website must not cancel the other's address. */
    openHandbookSite$ = createEffect(() => this.openSites(scope => scope === null));

    openPreview$ = createEffect(() => this.openSites(scope => scope !== null));

    /** Only the latest address of each counts: a website switched quickly is not overwritten by a late answer. */
    private openSites(belongs: (scope: TenantScope | null) => boolean) {
        return this.actions$.pipe(
            ofType(SiteActions.openSite),
            filter(({scope}) => belongs(scope)),
            switchMap(({scope, id}) =>
                this.siteService.address(scope, id).pipe(
                    map(url => SiteActions.openSiteSuccess({scope, siteId: id, url})),
                    catchError(error =>
                        of(
                            SiteActions.openSiteFailure({
                                scope,
                                error: this.errors.of(error, "errors.docSite.openSite"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        );
    }
}
