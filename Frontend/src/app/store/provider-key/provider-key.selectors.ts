import {createFeatureSelector, createSelector} from "@ngrx/store";
import {ProviderKeyState} from "./provider-key.reducer";

export const selectProviderKeyState = createFeatureSelector<ProviderKeyState>("providerKey");

export const selectProviderKeys = createSelector(selectProviderKeyState, state => state.keys);

export const selectProviderKeysLoading = createSelector(selectProviderKeyState, state => state.loading);
