import {Actions, ofType} from "@ngrx/effects";
import {MonoTypeOperatorFunction} from "rxjs";
import {repeat, takeUntil} from "rxjs/operators";
import * as AuthActions from "./auth/auth.actions";

/**
 * Ends what an effect runs or holds queued when the user logs out or switches to another tenant, and starts it afresh
 * for the next session. A request is sent with the token of the moment it starts: an answer still streaming would land
 * in the next user's chat, and uploads still queued would go out with the next user's token - into another tenant's
 * knowledge. A deletion queued behind another would remove the next user's object of the same id (ids repeat across
 * tenants). At a switch the new token is stored the moment the answer comes, before the page reloads - a queued request
 * starting in between would go out with it.
 */
export function endedByLogout<T>(actions$: Actions): MonoTypeOperatorFunction<T> {
    return source =>
        source.pipe(takeUntil(actions$.pipe(ofType(AuthActions.logout, AuthActions.switchTenantSuccess))), repeat());
}
