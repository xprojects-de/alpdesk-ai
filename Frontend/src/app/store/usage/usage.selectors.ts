import {createFeatureSelector, createSelector} from "@ngrx/store";
import {UsageState} from "./usage.reducer";

export const selectUsageState = createFeatureSelector<UsageState>("usage");

export const selectUsageMonth = createSelector(selectUsageState, state => state.month);

export const selectUsageRows = createSelector(selectUsageState, state => state.rows);

export const selectUsageLoading = createSelector(selectUsageState, state => state.loading);
