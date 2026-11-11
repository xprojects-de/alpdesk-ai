import {createAction} from "@ngrx/store";

// Backend-reachability polling: distinct from any single request's success/failure, this tracks
// whether the backend process itself can be reached at all (see components/dashboard for where
// it's shown to the operator).
export const startBackendHealthPolling = createAction("[BackendHealth] Start Polling");
export const stopBackendHealthPolling = createAction("[BackendHealth] Stop Polling");
export const checkBackendHealth = createAction("[BackendHealth] Check");
export const checkBackendHealthSuccess = createAction("[BackendHealth] Check Success");
export const checkBackendHealthFailure = createAction("[BackendHealth] Check Failure");
