import {AccessRequest} from "../../models/access-request.model";
import * as AccessRequestActions from "./access-request.actions";
import {accessRequestReducer, initialState} from "./access-request.reducer";

describe("accessRequestReducer", () => {
    const request = (id: number): AccessRequest => ({
        id,
        username: `user${id}`,
        displayName: `User ${id}`,
        requestedAt: "2026-10-07T08:00:00Z",
        language: null,
    });

    it("drops a request once it is assigned or rejected", () => {
        let state = accessRequestReducer(
            initialState,
            AccessRequestActions.loadAccessRequestsSuccess({requests: [request(1), request(2), request(3)]}),
        );
        state = accessRequestReducer(
            state,
            AccessRequestActions.assignAccessRequest({id: 1, assignment: {tenantId: 5, active: true, admin: false}}),
        );
        expect(state.loading).toBe(true);

        state = accessRequestReducer(state, AccessRequestActions.assignAccessRequestSuccess({id: 1}));
        state = accessRequestReducer(state, AccessRequestActions.rejectAccessRequestSuccess({id: 3}));

        expect(state.requests.map(r => r.id)).toEqual([2]);
        expect(state.loading).toBe(false);
    });

    it("keeps the requests when assigning fails", () => {
        let state = accessRequestReducer(
            initialState,
            AccessRequestActions.loadAccessRequestsSuccess({requests: [request(1)]}),
        );
        state = accessRequestReducer(
            state,
            AccessRequestActions.assignAccessRequest({id: 1, assignment: {tenantId: 5, active: false, admin: false}}),
        );
        state = accessRequestReducer(state, AccessRequestActions.assignAccessRequestFailure({error: "x"}));

        expect(state.requests).toEqual([request(1)]);
        expect(state.loading).toBe(false);
    });
});
