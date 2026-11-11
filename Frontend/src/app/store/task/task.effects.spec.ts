import {TestBed} from "@angular/core/testing";
import {provideMockActions} from "@ngrx/effects/testing";
import {HttpErrorResponse} from "@angular/common/http";
import {Action} from "@ngrx/store";
import {Subject, throwError, of} from "rxjs";
import {vi} from "vitest";
import {TaskService} from "../../services/task.service";
import {TaskRunDetail, TaskRunSummary} from "../../models/task.model";
import {translocoTesting} from "../../i18n/i18n.testing";
import * as TaskActions from "./task.actions";
import {TaskEffects} from "./task.effects";

describe("TaskEffects", () => {
    function setup(service: Partial<TaskService>) {
        const actions$ = new Subject<Action>();
        TestBed.configureTestingModule({
            imports: [translocoTesting("de")],
            providers: [TaskEffects, provideMockActions(() => actions$), {provide: TaskService, useValue: service}],
        });
        return {actions$, effects: TestBed.inject(TaskEffects)};
    }

    function runIn(state: TaskRunSummary["state"]): TaskRunDetail {
        return {run: {id: 7, state} as TaskRunSummary, steps: [], definition: [], answers: [], rows: []};
    }

    describe("watching a run", () => {
        beforeEach(() => vi.useFakeTimers());
        afterEach(() => vi.useRealTimers());

        it("goes on asking after one failed poll until the run has ended", () => {
            const answers = [
                of(runIn("RUNNING")),
                throwError(() => new HttpErrorResponse({status: 503})),
                of(runIn("FAILED")),
            ];
            const run = vi.fn(() => answers.shift() ?? of(runIn("FAILED")));
            const {actions$, effects} = setup({run});
            const emitted: Action[] = [];
            effects.watch$.subscribe(action => emitted.push(action));

            actions$.next(TaskActions.watchRun({scope: "self", taskId: 1, runId: 7}));
            vi.advanceTimersByTime(2000 * 2);

            expect(run).toHaveBeenCalledTimes(3);
            expect(emitted.map(action => action.type)).toEqual([
                TaskActions.loadRunSuccess.type,
                TaskActions.loadRunFailure.type,
                TaskActions.loadRunSuccess.type,
            ]);
        });

        it("waits for an answer slower than the interval instead of asking again over it", () => {
            const slow = new Subject<TaskRunDetail>();
            const run = vi.fn(() => slow);
            const {actions$, effects} = setup({run});
            const emitted: Action[] = [];
            effects.watch$.subscribe(action => emitted.push(action));

            actions$.next(TaskActions.watchRun({scope: "self", taskId: 1, runId: 7}));
            vi.advanceTimersByTime(2000 * 2);
            slow.next(runIn("DONE"));
            slow.complete();

            expect(run).toHaveBeenCalledTimes(1);
            expect(emitted.map(action => action.type)).toEqual([TaskActions.loadRunSuccess.type]);
        });

        it("stops asking once the run has ended", () => {
            const run = vi.fn(() => of(runIn("DONE")));
            const {actions$, effects} = setup({run});
            effects.watch$.subscribe();

            actions$.next(TaskActions.watchRun({scope: "self", taskId: 1, runId: 7}));
            vi.advanceTimersByTime(2000 * 3);

            expect(run).toHaveBeenCalledTimes(1);
        });
    });
});
