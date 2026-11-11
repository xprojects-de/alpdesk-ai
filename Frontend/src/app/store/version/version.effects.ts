import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, map, mergeMap} from "rxjs/operators";
import {VersionService} from "../../services/version.service";
import * as VersionActions from "./version.actions";
import {ErrorMessages} from "../../i18n/error-messages.service";

@Injectable()
export class VersionEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private versionService = inject(VersionService);

    loadVersion$ = createEffect(() =>
        this.actions$.pipe(
            ofType(VersionActions.loadVersion),
            mergeMap(() =>
                this.versionService.get().pipe(
                    map(version => VersionActions.loadVersionSuccess({version})),
                    catchError(error =>
                        of(
                            VersionActions.loadVersionFailure({
                                error: this.errors.of(error, "errors.version.loadVersion"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
