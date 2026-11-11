import {createAction, props} from "@ngrx/store";
import {AccessAssignment, AccessRequest} from "../../models/access-request.model";

export const loadAccessRequests = createAction("[Access Request] Load Access Requests");
export const loadAccessRequestsSuccess = createAction(
    "[Access Request] Load Access Requests Success",
    props<{requests: AccessRequest[]}>(),
);
export const loadAccessRequestsFailure = createAction(
    "[Access Request] Load Access Requests Failure",
    props<{error: string}>(),
);

export const assignAccessRequest = createAction(
    "[Access Request] Assign Access Request",
    props<{id: number; assignment: AccessAssignment}>(),
);
export const assignAccessRequestSuccess = createAction(
    "[Access Request] Assign Access Request Success",
    props<{id: number}>(),
);
export const assignAccessRequestFailure = createAction(
    "[Access Request] Assign Access Request Failure",
    props<{error: string}>(),
);

export const rejectAccessRequest = createAction("[Access Request] Reject Access Request", props<{id: number}>());
export const rejectAccessRequestSuccess = createAction(
    "[Access Request] Reject Access Request Success",
    props<{id: number}>(),
);
export const rejectAccessRequestFailure = createAction(
    "[Access Request] Reject Access Request Failure",
    props<{error: string}>(),
);
