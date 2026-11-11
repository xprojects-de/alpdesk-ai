import {ComponentType} from "@angular/cdk/portal";
import {DestroyRef, inject, Injectable, InjectionToken, Injector, signal} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {MatDialog, MatDialogConfig, MatDialogRef} from "@angular/material/dialog";
import {Actions} from "@ngrx/effects";
import {Action, ActionCreator, Store} from "@ngrx/store";
import {Observable} from "rxjs";
import {filter, map, take} from "rxjs/operators";

/** Saves a dialog's value; emits once: true = saved (close the dialog), false = failed (keep it open). */
export type DialogSaveHandler<R> = (value: R) => Observable<boolean>;

export const DIALOG_SAVE = new InjectionToken<DialogSaveHandler<unknown>>("DIALOG_SAVE");

/** What saving a dialog's value means in the store. */
export interface DialogSaveSpec<R> {
    /** The write to dispatch with the dialog's value. */
    action: (value: R) => Action;
    /** Actions that mean "saved". */
    success: ActionCreator[];
    /**
     * The action that means "failed". A slice-wide `failure` with a `load` flag only counts for
     * writes (load false) - a read failing meanwhile is not this save's answer.
     */
    failure: ActionCreator;
}

/**
 * Opens form dialogs that stay open until their value is saved. The dialog calls
 * `dialogSave().submit(value)`: the write is dispatched, and the dialog closes on its success
 * action. On failure it stays open with everything entered (the page shows the error as before).
 */
@Injectable({providedIn: "root"})
export class DialogSave {
    private dialog = inject(MatDialog);
    private store = inject(Store);
    private actions$ = inject(Actions);
    private injector = inject(Injector);

    open<C, D, R>(
        component: ComponentType<C>,
        config: MatDialogConfig<D>,
        spec: DialogSaveSpec<R>,
    ): MatDialogRef<C, R> {
        const handler: DialogSaveHandler<R> = value => this.save(spec, value);
        return this.dialog.open<C, D, R>(component, {
            ...config,
            injector: Injector.create({
                providers: [{provide: DIALOG_SAVE, useValue: handler}],
                parent: config.injector ?? this.injector,
            }),
        });
    }

    private save<R>(spec: DialogSaveSpec<R>, value: R): Observable<boolean> {
        const successTypes = spec.success.map(creator => creator.type);
        const answer$ = this.actions$.pipe(
            filter(
                action =>
                    successTypes.includes(action.type) ||
                    (action.type === spec.failure.type && (action as Action & {load?: boolean}).load !== true),
            ),
            take(1),
            map(action => successTypes.includes(action.type)),
        );
        // Listen before dispatching - the answer may come synchronously.
        return new Observable<boolean>(subscriber => {
            const subscription = answer$.subscribe(subscriber);
            this.store.dispatch(spec.action(value));
            return () => subscription.unsubscribe();
        });
    }
}

/**
 * For a form dialog: `submit(value)` saves through the handler of `DialogSave.open` and closes the
 * dialog only when saved; without a handler (a dialog opened the plain way) it just closes with the
 * value. `saving()` is true while the request runs - disable the save button meanwhile.
 */
export function dialogSave<R>() {
    const dialogRef = inject<MatDialogRef<unknown, R>>(MatDialogRef);
    const handler = inject(DIALOG_SAVE, {optional: true}) as DialogSaveHandler<R> | null;
    const destroyRef = inject(DestroyRef);
    const saving = signal(false);
    return {
        saving: saving.asReadonly(),
        submit(value: R): void {
            if (!handler) {
                dialogRef.close(value);
                return;
            }
            if (saving()) {
                return;
            }
            saving.set(true);
            dialogRef.disableClose = true;
            handler(value)
                .pipe(takeUntilDestroyed(destroyRef))
                .subscribe(saved => {
                    dialogRef.disableClose = false;
                    saving.set(false);
                    if (saved) {
                        dialogRef.close(value);
                    }
                });
        },
    };
}
