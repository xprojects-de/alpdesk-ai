import {createReducer, on} from "@ngrx/store";
import * as BackendHealthActions from "./backend-health.actions";

// A single failed check can be a one-off blip (a dropped packet, a momentary stall) rather than
// a real outage - requiring this many consecutive failures before flipping to "unreachable"
// avoids flashing the banner on for a false alarm. Any single success still resets immediately,
// so recovery stays fast even though going offline is deliberately debounced.
const CONSECUTIVE_FAILURES_BEFORE_UNREACHABLE = 2;

export interface BackendHealthState {
    // null = not checked yet (avoids briefly flashing "unreachable" before the first check lands)
    reachable: boolean | null;
    consecutiveFailures: number;
}

export const initialState: BackendHealthState = {
    reachable: null,
    consecutiveFailures: 0,
};

export const backendHealthReducer = createReducer(
    initialState,

    on(BackendHealthActions.checkBackendHealthSuccess, state => ({
        ...state,
        reachable: true,
        consecutiveFailures: 0,
    })),
    on(BackendHealthActions.checkBackendHealthFailure, state => {
        const consecutiveFailures = state.consecutiveFailures + 1;
        return {
            ...state,
            consecutiveFailures,
            reachable: consecutiveFailures >= CONSECUTIVE_FAILURES_BEFORE_UNREACHABLE ? false : state.reachable,
        };
    }),
);
