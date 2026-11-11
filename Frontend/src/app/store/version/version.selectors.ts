import {createFeatureSelector, createSelector} from "@ngrx/store";
import {VersionState} from "./version.reducer";

export const selectVersionState = createFeatureSelector<VersionState>("version");

export const selectVersion = createSelector(selectVersionState, state => state.version);
