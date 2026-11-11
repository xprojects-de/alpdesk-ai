import {createReducer, on} from "@ngrx/store";
import {AccessRequest} from "../../models/access-request.model";
import * as AccessRequestActions from "./access-request.actions";

export interface AccessRequestState {
    requests: AccessRequest[];
    loading: boolean;
}

export const initialState: AccessRequestState = {
    requests: [],
    loading: false,
};

export const accessRequestReducer = createReducer(
    initialState,

    on(
        AccessRequestActions.loadAccessRequests,
        AccessRequestActions.assignAccessRequest,
        AccessRequestActions.rejectAccessRequest,
        state => ({...state, loading: true}),
    ),
    on(
        AccessRequestActions.loadAccessRequestsFailure,
        AccessRequestActions.assignAccessRequestFailure,
        AccessRequestActions.rejectAccessRequestFailure,
        state => ({...state, loading: false}),
    ),
    on(AccessRequestActions.loadAccessRequestsSuccess, (_state, {requests}) => ({requests, loading: false})),
    // Assigned or rejected, the request is gone - assigned, it is now a user of its tenant.
    on(
        AccessRequestActions.assignAccessRequestSuccess,
        AccessRequestActions.rejectAccessRequestSuccess,
        (state, {id}) => ({requests: state.requests.filter(request => request.id !== id), loading: false}),
    ),
);
