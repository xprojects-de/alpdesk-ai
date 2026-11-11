import {createFeatureSelector, createSelector} from "@ngrx/store";
import {AccountState} from "./account.reducer";

export const selectAccountState = createFeatureSelector<AccountState>("account");

export const selectAccount = createSelector(selectAccountState, state => state.account);

export const selectAccountLoading = createSelector(selectAccountState, state => state.loading);

export const selectPasswordSaving = createSelector(selectAccountState, state => state.saving);
