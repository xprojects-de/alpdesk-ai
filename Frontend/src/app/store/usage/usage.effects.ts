import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, map, switchMap} from "rxjs/operators";
import {UsageService} from "../../services/usage.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as UsageActions from "./usage.actions";

@Injectable()
export class UsageEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private usageService = inject(UsageService);

    loadUsage$ = createEffect(() =>
        this.actions$.pipe(
            ofType(UsageActions.loadUsage),
            switchMap(({scope, month}) =>
                this.usageService.get(scope, month).pipe(
                    map(rows => UsageActions.loadUsageSuccess({month, rows})),
                    catchError(error =>
                        of(
                            UsageActions.loadUsageFailure({
                                error: this.errors.of(error, "errors.usage.loadUsage"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
