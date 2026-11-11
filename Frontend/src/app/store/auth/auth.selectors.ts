import {createFeatureSelector, createSelector} from "@ngrx/store";
import {AuthState} from "../../models/auth.model";
import {homePathFor} from "../../utils/auth.util";

export const selectAuthState = createFeatureSelector<AuthState>("auth");

export const selectIsAuthenticated = createSelector(selectAuthState, state => state.isAuthenticated);

export const selectAuthUsername = createSelector(selectAuthState, state => state.username);

export const selectAuthLoading = createSelector(selectAuthState, state => state.loading);

export const selectAuthError = createSelector(selectAuthState, state => state.error);

export const selectAuthRoles = createSelector(selectAuthState, state => state.roles);

export const selectWamEnabled = createSelector(selectAuthState, state => state.wamEnabled);

export const selectWamGroupsChecked = createSelector(selectAuthState, state => state.wamGroupsChecked);

export const selectWamWaiting = createSelector(selectAuthState, state => state.wamWaiting);

export const selectWamError = createSelector(selectAuthState, state => state.wamError);

/** Where a logged-in user belongs (see homePathFor); null while not logged in. */
export const selectHomePath = createSelector(selectAuthState, state =>
    state.isAuthenticated ? homePathFor(state.roles) : null,
);

export const selectWamChoice = createSelector(selectAuthState, state => state.wamChoice);

export const selectSwitchError = createSelector(selectAuthState, state => state.switchError);
