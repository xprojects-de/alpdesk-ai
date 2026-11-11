import {KnowledgeSource} from "../../models/knowledge-source.model";
import * as SourceActions from "./knowledge-source.actions";
import {initialState, knowledgeSourceReducer} from "./knowledge-source.reducer";
import {selectAnySourceRunning, selectSourceNames} from "./knowledge-source.selectors";

describe("knowledgeSourceReducer", () => {
    const source = (id: number, name: string, change: Partial<KnowledgeSource> = {}): KnowledgeSource => ({
        id,
        name,
        fileStoreId: 1,
        fileStore: "Ablage",
        folder: "",
        areaId: null,
        area: null,
        pattern: null,
        schedule: "DAILY",
        active: true,
        nextRunAt: null,
        running: false,
        lastStartedAt: null,
        lastFinishedAt: null,
        lastError: null,
        lastAdded: 0,
        lastChanged: 0,
        lastDeleted: 0,
        lastRefused: 0,
        lastSkipped: 0,
        lastSucceededAt: null,
        openFiles: 0,
        openBytes: 0,
        ...change,
    });

    function loaded(sources: KnowledgeSource[]) {
        const state = knowledgeSourceReducer(initialState, SourceActions.loadSources({scope: 1}));
        return knowledgeSourceReducer(state, SourceActions.loadSourcesSuccess({scope: 1, sources}));
    }

    it("keeps the sources by name; a created, updated or started one takes its place", () => {
        let state = loaded([source(1, "Projekte")]);
        state = knowledgeSourceReducer(
            state,
            SourceActions.createSourceSuccess({scope: 1, source: source(2, "Archiv")}),
        );
        expect(state.sources.map(s => s.name)).toEqual(["Archiv", "Projekte"]);

        state = knowledgeSourceReducer(
            state,
            SourceActions.runSourceSuccess({scope: 1, source: source(1, "Projekte", {running: true})}),
        );
        expect(state.sources.find(s => s.id === 1)?.running).toBe(true);

        state = knowledgeSourceReducer(state, SourceActions.deleteSourceSuccess({scope: 1, sourceId: 2}));
        expect(state.sources.map(s => s.id)).toEqual([1]);
    });

    it("ignores another tenant's results", () => {
        const state = loaded([source(1, "Projekte")]);
        const other = knowledgeSourceReducer(
            state,
            SourceActions.loadSourcesSuccess({scope: 2, sources: [source(9, "Fremd")]}),
        );
        expect(other).toBe(state);
    });

    it("loads the open list's files, ignores another tenant's, and marks a failed load", () => {
        const loading = knowledgeSourceReducer(
            loaded([source(1, "Projekte")]),
            SourceActions.loadFiles({scope: 1, sourceId: 1}),
        );
        expect(loading.files).toBeNull();
        expect(loading.filesFailed).toBe(false);
        const files = [{path: "a.pdf", size: 1, modified: null, reason: "document.tooLarge"}];
        expect(
            knowledgeSourceReducer(loading, SourceActions.loadFilesSuccess({scope: 2, sourceId: 1, files})).files,
        ).toBeNull();
        expect(
            knowledgeSourceReducer(loading, SourceActions.loadFilesSuccess({scope: 1, sourceId: 1, files})).files,
        ).toEqual({
            sourceId: 1,
            files,
        });
        expect(knowledgeSourceReducer(loading, SourceActions.loadFilesFailure({error: "x"})).filesFailed).toBe(true);
    });

    it("knows whether a source runs and names them by id", () => {
        const state = {knowledgeSource: loaded([source(1, "Projekte"), source(2, "Archiv", {running: true})])};
        expect(selectAnySourceRunning(state)).toBe(true);
        expect(selectSourceNames(state)).toEqual({1: "Projekte", 2: "Archiv"});
    });
});
