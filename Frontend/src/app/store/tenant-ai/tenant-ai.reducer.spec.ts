import {AiSettings} from "../../models/ai.model";
import * as TenantAiActions from "./tenant-ai.actions";
import {initialState, tenantAiReducer} from "./tenant-ai.reducer";

describe("tenantAiReducer - search language", () => {
    const settings = (searchLanguage: AiSettings["searchLanguage"]): AiSettings => ({
        embeddingModelId: 1,
        rerankingModelId: null,
        searchLanguage,
        globalKeysAllowed: false,
    });

    it("saves while the change is on its way and takes the settings the backend answers", () => {
        let state = tenantAiReducer(initialState, TenantAiActions.loadSettingsSuccess({settings: settings("GERMAN")}));
        state = tenantAiReducer(state, TenantAiActions.setSearchLanguage({language: "ENGLISH"}));
        expect(state.savingLanguage).toBe(true);
        expect(state.saving).toBe(false);

        state = tenantAiReducer(state, TenantAiActions.setSearchLanguageSuccess({settings: settings("ENGLISH")}));
        expect(state.savingLanguage).toBe(false);
        expect(state.settings?.searchLanguage).toBe("ENGLISH");
    });

    it("keeps the stored language when the change fails", () => {
        let state = tenantAiReducer(initialState, TenantAiActions.loadSettingsSuccess({settings: settings("GERMAN")}));
        state = tenantAiReducer(state, TenantAiActions.setSearchLanguage({language: "ENGLISH"}));
        state = tenantAiReducer(state, TenantAiActions.setSearchLanguageFailure({error: "x"}));

        expect(state.savingLanguage).toBe(false);
        expect(state.settings?.searchLanguage).toBe("GERMAN");
    });
});

describe("tenantAiReducer - reranker", () => {
    const settings = (rerankingModelId: number | null): AiSettings => ({
        embeddingModelId: 1,
        rerankingModelId,
        searchLanguage: "GERMAN",
        globalKeysAllowed: false,
    });

    it("saves while the change is on its way and takes the settings the backend answers", () => {
        let state = tenantAiReducer(initialState, TenantAiActions.loadSettingsSuccess({settings: settings(null)}));
        state = tenantAiReducer(state, TenantAiActions.setRerankingModel({modelId: 7}));
        expect(state.savingReranker).toBe(true);
        expect(state.saving).toBe(false);

        state = tenantAiReducer(state, TenantAiActions.setRerankingModelSuccess({settings: settings(7)}));
        expect(state.savingReranker).toBe(false);
        expect(state.settings?.rerankingModelId).toBe(7);
    });

    it("keeps the stored reranker when switching it off fails", () => {
        let state = tenantAiReducer(initialState, TenantAiActions.loadSettingsSuccess({settings: settings(7)}));
        state = tenantAiReducer(state, TenantAiActions.setRerankingModel({modelId: null}));
        state = tenantAiReducer(state, TenantAiActions.setRerankingModelFailure({error: "x"}));

        expect(state.savingReranker).toBe(false);
        expect(state.settings?.rerankingModelId).toBe(7);
    });
});
