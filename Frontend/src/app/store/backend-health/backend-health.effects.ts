import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {interval, of} from "rxjs";
import {catchError, exhaustMap, map, switchMap, takeUntil, timeout} from "rxjs/operators";
import {HealthService} from "../../services/health.service";
import * as BackendHealthActions from "./backend-health.actions";

@Injectable()
export class BackendHealthEffects {
    private actions$ = inject(Actions);
    private healthService = inject(HealthService);

    // Every 5s: the 2-consecutive-failures debounce in the reducer keeps this from being noisy, and
    // /watchdog is essentially free for the backend to answer.
    startBackendHealthPolling$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BackendHealthActions.startBackendHealthPolling),
            switchMap(() =>
                interval(5000).pipe(
                    map(() => BackendHealthActions.checkBackendHealth()),
                    takeUntil(this.actions$.pipe(ofType(BackendHealthActions.stopBackendHealthPolling))),
                ),
            ),
        ),
    );

    checkBackendHealth$ = createEffect(() =>
        this.actions$.pipe(
            ofType(BackendHealthActions.checkBackendHealth),
            // exhaustMap + a short timeout: against a hung (not refusing) backend, pings would
            // otherwise pile up until the global 30s HTTP timeout, occupying the browser's per-host
            // connection slots and delaying the "unreachable" banner to ~35s.
            exhaustMap(() =>
                this.healthService.ping().pipe(
                    timeout(4000),
                    map(() => BackendHealthActions.checkBackendHealthSuccess()),
                    catchError(() => of(BackendHealthActions.checkBackendHealthFailure())),
                ),
            ),
        ),
    );
}
