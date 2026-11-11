import {createFeatureSelector, createSelector} from "@ngrx/store";
import {AccessRequestState} from "./access-request.reducer";

export const selectAccessRequestState = createFeatureSelector<AccessRequestState>("accessRequest");

export const selectAccessRequests = createSelector(selectAccessRequestState, state => state.requests);

export const selectAccessRequestsLoading = createSelector(selectAccessRequestState, state => state.loading);
