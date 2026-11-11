import {inject} from "@angular/core";
import {CanDeactivateFn} from "@angular/router";
import {Store} from "@ngrx/store";
import {of} from "rxjs";
import {switchMap, take} from "rxjs/operators";
import {TaskEditorComponent} from "../components/task-editor/task-editor.component";
import * as AuthSelectors from "../store/auth/auth.selectors";

/**
 * Leaving the task editor with unsaved changes is asked about first - unless the session is over (a logout, a 401):
 * nothing could be saved any more, and a "no" would keep the user on a page whose store was emptied.
 */
export const taskEditorGuard: CanDeactivateFn<TaskEditorComponent> = component =>
    inject(Store)
        .select(AuthSelectors.selectIsAuthenticated)
        .pipe(
            take(1),
            switchMap(authenticated => (authenticated ? component.mayLeave() : of(true))),
        );
