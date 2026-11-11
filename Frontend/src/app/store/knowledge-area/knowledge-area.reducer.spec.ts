import {KnowledgeArea} from "../../models/knowledge-area.model";
import * as AreaActions from "./knowledge-area.actions";
import {initialState, knowledgeAreaReducer} from "./knowledge-area.reducer";

describe("knowledgeAreaReducer", () => {
    const sales: KnowledgeArea = {id: 1, name: "Vertrieb", createdAt: ""};
    const workshop: KnowledgeArea = {id: 2, name: "Werkstatt", createdAt: ""};

    it("keeps the areas by name as they are created, renamed and deleted", () => {
        let state = knowledgeAreaReducer(initialState, AreaActions.loadAreas({scope: 1}));
        state = knowledgeAreaReducer(state, AreaActions.loadAreasSuccess({scope: 1, areas: [workshop]}));
        state = knowledgeAreaReducer(state, AreaActions.createAreaSuccess({scope: 1, area: sales}));
        expect(state.areas.map(area => area.name)).toEqual(["Vertrieb", "Werkstatt"]);

        state = knowledgeAreaReducer(
            state,
            AreaActions.renameAreaSuccess({scope: 1, area: {...sales, name: "Zentrale"}}),
        );
        expect(state.areas.map(area => area.name)).toEqual(["Werkstatt", "Zentrale"]);

        state = knowledgeAreaReducer(state, AreaActions.deleteAreaSuccess({scope: 1, id: 2}));
        expect(state.areas.map(area => area.id)).toEqual([1]);
    });

    it("forgets another tenant's areas when one is opened and ignores late results for it", () => {
        let state = knowledgeAreaReducer(initialState, AreaActions.loadAreas({scope: 1}));
        state = knowledgeAreaReducer(state, AreaActions.loadAreasSuccess({scope: 1, areas: [sales]}));
        state = knowledgeAreaReducer(state, AreaActions.loadAreas({scope: 2}));
        expect(state.areas).toEqual([]);
        expect(knowledgeAreaReducer(state, AreaActions.loadAreasSuccess({scope: 1, areas: [sales]})).areas).toEqual([]);
    });

    it("knows the areas only once they are answered for the scope - not while loading, not after a failure", () => {
        let state = knowledgeAreaReducer(initialState, AreaActions.loadAreas({scope: 1}));
        expect(state.loaded).toBe(false);
        expect(knowledgeAreaReducer(state, AreaActions.loadAreasFailure({error: "503"})).loaded).toBe(false);

        state = knowledgeAreaReducer(state, AreaActions.loadAreasSuccess({scope: 1, areas: []}));
        expect(state.loaded).toBe(true);
        expect(knowledgeAreaReducer(state, AreaActions.loadAreas({scope: 1})).loaded).toBe(true);
        expect(knowledgeAreaReducer(state, AreaActions.loadAreas({scope: 2})).loaded).toBe(false);
    });

    it("ignores areas answered after the logout, before the next login opened them", () => {
        expect(knowledgeAreaReducer(initialState, AreaActions.loadAreasSuccess({scope: "self", areas: [sales]}))).toBe(
            initialState,
        );
    });
});
