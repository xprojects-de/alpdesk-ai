import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, exhaustMap, map, switchMap} from "rxjs/operators";
import {ApplicationLogService} from "../../services/application-log.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as ApplicationLogActions from "./application-log.actions";

@Injectable()
export class ApplicationLogEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private service = inject(ApplicationLogService);

    loadEntries$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ApplicationLogActions.loadEntries),
            switchMap(({filter}) =>
                this.service.getRecent(filter).pipe(
                    map(response => ApplicationLogActions.loadEntriesSuccess({response})),
                    catchError(error =>
                        of(
                            ApplicationLogActions.loadEntriesFailure({
                                error: this.errors.of(error, "errors.applicationLog.loadEntries"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    /** exhaustMap: a second click while the report is being sent sends no second one. */
    sendDailyReport$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ApplicationLogActions.sendDailyReport),
            exhaustMap(() =>
                this.service.sendDailyReport().pipe(
                    map(({recipients}) => ApplicationLogActions.sendDailyReportSuccess({recipients})),
                    catchError(error =>
                        of(
                            ApplicationLogActions.sendDailyReportFailure({
                                error: this.errors.of(error, "errors.applicationLog.sendDailyReport"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
