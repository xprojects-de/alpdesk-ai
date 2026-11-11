import {createFeatureSelector, createSelector} from "@ngrx/store";
import {BackendHealthState} from "./backend-health.reducer";

export const selectBackendHealthState = createFeatureSelector<BackendHealthState>("backendHealth");

export const selectBackendReachable = createSelector(selectBackendHealthState, state => state.reachable);
