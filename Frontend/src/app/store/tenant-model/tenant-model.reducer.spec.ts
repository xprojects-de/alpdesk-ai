import * as TenantModelActions from "./tenant-model.actions";
import {initialState, tenantModelReducer} from "./tenant-model.reducer";

describe("tenantModelReducer", () => {
    const loadedFor = (tenantId: number, modelIds: number[]) =>
        tenantModelReducer(
            tenantModelReducer(initialState, TenantModelActions.loadTenantModels({tenantId})),
            TenantModelActions.loadTenantModelsSuccess({tenantId, modelIds}),
        );

    it("drops the models of another tenant when a tenant's dialog opens", () => {
        const state = tenantModelReducer(loadedFor(1, [1, 2]), TenantModelActions.loadTenantModels({tenantId: 2}));

        expect(state).toEqual({tenantId: 2, modelIds: null, loading: true});
    });

    it("ignores results for a tenant whose dialog is no longer open", () => {
        const state = tenantModelReducer(initialState, TenantModelActions.loadTenantModels({tenantId: 2}));

        expect(
            tenantModelReducer(state, TenantModelActions.loadTenantModelsSuccess({tenantId: 1, modelIds: [1]})),
        ).toBe(state);
        expect(
            tenantModelReducer(state, TenantModelActions.assignTenantModelsSuccess({tenantId: 1, modelIds: [1]})),
        ).toBe(state);
    });

    it("takes the assignment the backend answers with", () => {
        let state = tenantModelReducer(
            loadedFor(1, [1]),
            TenantModelActions.assignTenantModels({tenantId: 1, modelIds: [1, 2]}),
        );
        state = tenantModelReducer(
            state,
            TenantModelActions.assignTenantModelsSuccess({tenantId: 1, modelIds: [1, 2]}),
        );

        expect(state).toEqual({tenantId: 1, modelIds: [1, 2], loading: false});
    });

    it("keeps the loaded assignment when saving fails", () => {
        let state = tenantModelReducer(
            loadedFor(1, [1]),
            TenantModelActions.assignTenantModels({tenantId: 1, modelIds: []}),
        );
        state = tenantModelReducer(state, TenantModelActions.assignTenantModelsFailure({error: "x"}));

        expect(state).toEqual({tenantId: 1, modelIds: [1], loading: false});
    });
});
