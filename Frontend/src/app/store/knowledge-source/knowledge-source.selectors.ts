import {createFeatureSelector, createSelector} from "@ngrx/store";
import {KnowledgeSourceState} from "./knowledge-source.reducer";

export const selectKnowledgeSourceState = createFeatureSelector<KnowledgeSourceState>("knowledgeSource");

export const selectSources = createSelector(selectKnowledgeSourceState, state => state.sources);

export const selectSourcesLoading = createSelector(selectKnowledgeSourceState, state => state.loading);

export const selectFiles = createSelector(selectKnowledgeSourceState, state => state.files);

export const selectFilesFailed = createSelector(selectKnowledgeSourceState, state => state.filesFailed);

/** Whether a run is under way - the list is reloaded while one is. */
export const selectAnySourceRunning = createSelector(selectSources, sources => sources.some(source => source.running));

/** The sources' names by id - the document list names where a document comes from. */
export const selectSourceNames = createSelector(selectSources, sources =>
    Object.fromEntries(sources.map(source => [source.id, source.name])),
);
