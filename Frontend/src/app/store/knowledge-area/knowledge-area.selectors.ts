import {createFeatureSelector, createSelector} from "@ngrx/store";
import {KnowledgeAreaState} from "./knowledge-area.reducer";

export const selectKnowledgeAreaState = createFeatureSelector<KnowledgeAreaState>("knowledgeArea");

export const selectAreas = createSelector(selectKnowledgeAreaState, state => state.areas);

export const selectAreasLoaded = createSelector(selectKnowledgeAreaState, state => state.loaded);
