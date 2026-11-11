import {createFeatureSelector, createSelector} from "@ngrx/store";
import {ApplicationLogState} from "./application-log.reducer";

export const selectApplicationLogState = createFeatureSelector<ApplicationLogState>("applicationLog");

export const selectEntries = createSelector(selectApplicationLogState, state => state.entries);
export const selectCapacity = createSelector(selectApplicationLogState, state => state.capacity);
export const selectStartedAt = createSelector(selectApplicationLogState, state => state.startedAt);
export const selectLoading = createSelector(selectApplicationLogState, state => state.loading);
export const selectFilter = createSelector(selectApplicationLogState, state => state.filter);
export const selectSending = createSelector(selectApplicationLogState, state => state.sending);
