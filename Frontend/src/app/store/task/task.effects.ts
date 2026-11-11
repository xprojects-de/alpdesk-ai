import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {Observable, of, timer} from "rxjs";
import {catchError, concatMap, exhaustMap, map, switchMap, takeUntil, takeWhile} from "rxjs/operators";
import {Action} from "@ngrx/store";
import {TaskService} from "../../services/task.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import {isOpen} from "../../utils/task.util";
import {endedByLogout} from "../session.operator";
import * as TaskActions from "./task.actions";

/** How often the run view asks again while its run is open. */
const RUN_POLL_MS = 2000;
const RUNS_PAGE = 50;

@Injectable()
export class TaskEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private service = inject(TaskService);

    load$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.loadTasks),
            switchMap(({scope}) =>
                this.service.list(scope).pipe(
                    map(tasks => TaskActions.loadTasksSuccess({scope, tasks})),
                    this.failWith(error => TaskActions.loadTasksFailure({error}), "errors.task.load"),
                ),
            ),
        ),
    );

    open$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.openTask),
            switchMap(({scope, taskId}) =>
                this.service.get(scope, taskId).pipe(
                    map(task => TaskActions.openTaskSuccess({scope, task})),
                    this.failWith(error => TaskActions.loadTaskFailure({error}), "errors.task.loadOne"),
                ),
            ),
        ),
    );

    /** A task opened or saved: its runs, and the graph of its text. */
    afterOpen$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.openTaskSuccess, TaskActions.saveTaskSuccess),
            concatMap(({scope, task}) => [
                TaskActions.loadRuns({scope, taskId: task.id}),
                TaskActions.validateTask({scope, source: task.source}),
            ]),
        ),
    );

    validate$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.validateTask),
            switchMap(({scope, source}) =>
                this.service.validate(scope, source).pipe(
                    map(validation => TaskActions.validateTaskSuccess({scope, validation})),
                    this.failWith(error => TaskActions.validateTaskFailure({error}), "errors.task.validate"),
                ),
            ),
        ),
    );

    save$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.saveTask),
            concatMap(({scope, taskId, request}) =>
                (taskId === null
                    ? this.service.create(scope, request)
                    : this.service.update(scope, taskId, request)
                ).pipe(
                    map(task => TaskActions.saveTaskSuccess({scope, task})),
                    this.failWith(error => TaskActions.saveTaskFailure({error}), "errors.task.save"),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    delete$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.deleteTask),
            concatMap(({scope, taskId}) =>
                this.service.delete(scope, taskId).pipe(
                    map(() => TaskActions.deleteTaskSuccess({scope, taskId})),
                    this.failWith(error => TaskActions.deleteTaskFailure({error}), "errors.task.delete"),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    start$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.startTask),
            concatMap(({scope, taskId}) =>
                this.service.start(scope, taskId).pipe(
                    map(({runId}) => TaskActions.startTaskSuccess({scope, taskId, runId})),
                    this.failWith(error => TaskActions.startTaskFailure({error}), "errors.task.start"),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    /** A run started, cancelled or deleted: the list of runs anew, and the list of tasks for their last runs. */
    reloadRuns$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.startTaskSuccess, TaskActions.cancelRunSuccess, TaskActions.deleteRunsSuccess),
            concatMap(({scope, taskId}) => [TaskActions.loadRuns({scope, taskId}), TaskActions.loadTasks({scope})]),
        ),
    );

    runs$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.loadRuns),
            switchMap(({scope, taskId}) =>
                this.service.runs(scope, taskId, RUNS_PAGE).pipe(
                    map(runs => TaskActions.loadRunsSuccess({scope, taskId, runs})),
                    this.failWith(error => TaskActions.loadRunsFailure({error}), "errors.task.loadRuns"),
                ),
            ),
        ),
    );

    /** The run view asks again every 2 s while the run is open - until it closes or the user logs out. */
    watch$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.watchRun),
            switchMap(({scope, taskId, runId}) =>
                timer(0, RUN_POLL_MS).pipe(
                    // One failed poll - a 503 "try again", a timeout - is reported, and the next one asks again:
                    // the view would otherwise show the run as open for good. An answer slower than the interval is
                    // waited for: a new poll over it would cancel every request on a slow link.
                    exhaustMap(() =>
                        this.service.run(scope, taskId, runId).pipe(
                            map(run => ({run, error: null})),
                            catchError(error => of({run: null, error: this.errors.of(error, "errors.task.loadRun")})),
                        ),
                    ),
                    takeWhile(({run}) => run === null || isOpen(run.run.state), true),
                    map(({run, error}) =>
                        run
                            ? TaskActions.loadRunSuccess({scope, run})
                            : TaskActions.loadRunFailure({error: error ?? ""}),
                    ),
                    takeUntil(this.actions$.pipe(ofType(TaskActions.watchRunStop))),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    cancel$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.cancelRun),
            concatMap(({scope, taskId, runId}) =>
                this.service.cancel(scope, taskId, runId).pipe(
                    map(() => TaskActions.cancelRunSuccess({scope, taskId, runId})),
                    this.failWith(error => TaskActions.cancelRunFailure({error}), "errors.task.cancel"),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    deleteRuns$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.deleteRuns),
            concatMap(({scope, taskId, runId}) =>
                (runId === null
                    ? this.service.deleteRuns(scope, taskId)
                    : this.service.deleteRun(scope, taskId, runId)
                ).pipe(
                    map(() => TaskActions.deleteRunsSuccess({scope, taskId})),
                    this.failWith(error => TaskActions.deleteRunsFailure({error}), "errors.task.deleteRuns"),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    draftModels$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.loadDraftModels),
            switchMap(({scope}) =>
                this.service.draftModels(scope).pipe(
                    map(models => TaskActions.loadDraftModelsSuccess({scope, models})),
                    this.failWith(error => TaskActions.loadDraftModelsFailure({error}), "errors.task.draftModels"),
                ),
            ),
        ),
    );

    draft$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.draftTask),
            switchMap(({scope, request}) =>
                this.service.draft(scope, request).pipe(
                    map(draft => TaskActions.draftTaskSuccess({scope, draft})),
                    this.failWith(error => TaskActions.draftTaskFailure({error}), "errors.task.draft"),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    reference$ = createEffect(() =>
        this.actions$.pipe(
            ofType(TaskActions.loadReference),
            switchMap(({scope, language}) =>
                this.service.reference(scope, language).pipe(
                    map(({markdown}) => TaskActions.loadReferenceSuccess({markdown})),
                    this.failWith(error => TaskActions.loadReferenceFailure({error}), "errors.task.reference"),
                ),
            ),
        ),
    );

    private failWith<T>(failure: (error: string) => Action, fallback: string) {
        return catchError<T, Observable<Action>>(error => of(failure(this.errors.of(error, fallback))));
    }
}
