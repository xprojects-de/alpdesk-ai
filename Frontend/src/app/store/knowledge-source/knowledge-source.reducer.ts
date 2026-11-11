import {createReducer, on} from "@ngrx/store";
import {KnowledgeSource, SourceFile} from "../../models/knowledge-source.model";
import {TenantScope} from "../../models/tenant.model";
import * as SourceActions from "./knowledge-source.actions";

/** One tenant's knowledge sources at a time: the tenant admin's own, or the tenant the admin opened. */
export interface KnowledgeSourceState {
    scope: TenantScope | null;
    sources: KnowledgeSource[];
    loading: boolean;
    /** The files without a document of the source whose list is open; null while none is or it loads. */
    files: {sourceId: number; files: SourceFile[]} | null;
    /** The open list could not be loaded - the dialog says so instead of waiting on. */
    filesFailed: boolean;
}

export const initialState: KnowledgeSourceState = {
    scope: null,
    sources: [],
    loading: false,
    files: null,
    filesFailed: false,
};

function replace(sources: KnowledgeSource[], source: KnowledgeSource): KnowledgeSource[] {
    return sources.map(existing => (existing.id === source.id ? source : existing));
}

function byName(sources: KnowledgeSource[]): KnowledgeSource[] {
    return [...sources].sort((a, b) => a.name.localeCompare(b.name));
}

export const knowledgeSourceReducer = createReducer(
    initialState,

    on(SourceActions.loadSources, (state, {scope}) =>
        state.scope === scope ? {...state, loading: true} : {...initialState, scope, loading: true},
    ),
    // Results for another tenant (the admin opened another one's meanwhile) are ignored.
    on(SourceActions.loadSourcesSuccess, (state, {scope, sources}) =>
        state.scope === scope ? {...state, sources, loading: false} : state,
    ),
    on(SourceActions.loadSourcesFailure, state => ({...state, loading: false})),

    on(SourceActions.createSourceSuccess, (state, {scope, source}) =>
        state.scope === scope ? {...state, sources: byName([...state.sources, source])} : state,
    ),
    on(SourceActions.updateSourceSuccess, SourceActions.runSourceSuccess, (state, {scope, source}) =>
        state.scope === scope ? {...state, sources: byName(replace(state.sources, source))} : state,
    ),
    on(SourceActions.deleteSourceSuccess, (state, {scope, sourceId}) =>
        state.scope === scope ? {...state, sources: state.sources.filter(s => s.id !== sourceId)} : state,
    ),

    on(SourceActions.loadFiles, state => ({...state, files: null, filesFailed: false})),
    on(SourceActions.loadFilesSuccess, (state, {scope, sourceId, files}) =>
        state.scope === scope ? {...state, files: {sourceId, files}} : state,
    ),
    on(SourceActions.loadFilesFailure, state => ({...state, filesFailed: true})),
);
