import {ActionReducer} from "@ngrx/store";
import * as AuthActions from "./auth/auth.actions";

/**
 * Slices that hold nothing of a session: the app version, whether the backend is reachable, and the look - whose
 * reducer drops the tenant's part itself at logout (the login page keeps the installation's).
 */
const KEPT = ["version", "backendHealth", "branding"];

/** Actions after which nothing of the session before may stay: a logout, and the switch to another tenant. */
const SESSION_ENDS: string[] = [AuthActions.logoutSuccess.type, AuthActions.switchTenantSuccess.type];

/**
 * On logout every slice goes back to its initial state (except {@link KEPT}): tenants and their API
 * keys must not stay in the store for the next login in the same tab. The same at the switch to another tenant - the
 * auth slice then takes the new token from the action.
 */
export function clearOnLogout<S extends Record<string, unknown>>(reducer: ActionReducer<S>): ActionReducer<S> {
    return (state, action) => {
        if (SESSION_ENDS.includes(action.type) && state) {
            const kept = Object.fromEntries(KEPT.filter(key => key in state).map(key => [key, state[key]]));
            return reducer(kept as S, action);
        }
        return reducer(state, action);
    };
}
