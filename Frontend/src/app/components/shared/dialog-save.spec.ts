import {Injector, runInInjectionContext} from "@angular/core";
import {TestBed} from "@angular/core/testing";
import {MatDialog, MatDialogRef} from "@angular/material/dialog";
import {provideMockActions} from "@ngrx/effects/testing";
import {Action, createAction, props, Store} from "@ngrx/store";
import {Subject} from "rxjs";
import {vi} from "vitest";
import {DIALOG_SAVE, DialogSave, dialogSave, DialogSaveHandler} from "./dialog-save";

const save = createAction("[Test] Save", props<{name: string}>());
const saveSuccess = createAction("[Test] Save Success");
const failure = createAction("[Test] Failure", props<{error: string; load: boolean}>());

describe("dialogSave", () => {
    function setup(handler: DialogSaveHandler<string> | null) {
        const dialogRef = {close: vi.fn(), disableClose: false};
        TestBed.configureTestingModule({
            providers: [
                {provide: MatDialogRef, useValue: dialogRef},
                ...(handler ? [{provide: DIALOG_SAVE, useValue: handler}] : []),
            ],
        });
        const saver = runInInjectionContext(TestBed.inject(Injector), () => dialogSave<string>());
        return {dialogRef, saver};
    }

    it("closes right away without a save handler", () => {
        const {dialogRef, saver} = setup(null);
        saver.submit("Haus");
        expect(dialogRef.close).toHaveBeenCalledWith("Haus");
    });

    it("stays open while saving and after a failure, closes when saved", () => {
        const answers = new Subject<boolean>();
        const {dialogRef, saver} = setup(() => answers);

        saver.submit("Haus");
        expect(saver.saving()).toBe(true);
        expect(dialogRef.disableClose).toBe(true);

        answers.next(false);
        expect(dialogRef.close).not.toHaveBeenCalled();
        expect(saver.saving()).toBe(false);

        saver.submit("Haus 2");
        answers.next(true);
        expect(dialogRef.close).toHaveBeenCalledWith("Haus 2");
    });
});

describe("DialogSave", () => {
    let actions$: Subject<Action>;
    let dispatched: Action[];
    let handler: DialogSaveHandler<string>;

    beforeEach(() => {
        actions$ = new Subject<Action>();
        dispatched = [];
        const dialog = {
            open: vi.fn((_: unknown, config: {injector: Injector}) => (handler = config.injector.get(DIALOG_SAVE))),
        };
        TestBed.configureTestingModule({
            providers: [
                provideMockActions(() => actions$),
                {provide: Store, useValue: {dispatch: (action: Action) => dispatched.push(action)}},
                {provide: MatDialog, useValue: dialog},
            ],
        });
        TestBed.inject(DialogSave).open(
            class {},
            {},
            {
                action: (name: string) => save({name}),
                success: [saveSuccess],
                failure,
            },
        );
    });

    it("dispatches the write and answers with its outcome", () => {
        const answers: boolean[] = [];
        handler("Haus").subscribe(saved => answers.push(saved));
        expect(dispatched).toEqual([save({name: "Haus"})]);

        actions$.next(failure({error: "Laden fehlgeschlagen", load: true})); // a read - not this save's answer
        expect(answers).toEqual([]);
        actions$.next(failure({error: "externalId vergeben", load: false}));
        expect(answers).toEqual([false]);

        handler("Haus").subscribe(saved => answers.push(saved));
        actions$.next(saveSuccess());
        expect(answers).toEqual([false, true]);
    });
});
