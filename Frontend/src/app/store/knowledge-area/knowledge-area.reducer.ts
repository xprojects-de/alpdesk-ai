import {createReducer, on} from "@ngrx/store";
import {KnowledgeArea} from "../../models/knowledge-area.model";
import {TenantScope} from "../../models/tenant.model";
import {sortAreas} from "../../utils/knowledge-area.util";
import * as AreaActions from "./knowledge-area.actions";

/** One tenant's knowledge areas at a time: the tenant admin's own, or those of the tenant the admin opened. */
export interface KnowledgeAreaState {
    scope: TenantScope | null;
    areas: KnowledgeArea[];
    loading: boolean;
    /** The scope's areas have been answered - until then an upload cannot ask where it goes. */
    loaded: boolean;
}

export const initialState: KnowledgeAreaState = {
    scope: null,
    areas: [],
    loading: false,
    loaded: false,
};

export const knowledgeAreaReducer = createReducer(
    initialState,

    on(AreaActions.loadAreas, (state, {scope}) => ({
        ...state,
        scope,
        areas: state.scope === scope ? state.areas : [],
        loading: true,
        loaded: state.scope === scope && state.loaded,
    })),
    // Results for another tenant (the admin opened another one's meanwhile) are ignored.
    on(AreaActions.loadAreasSuccess, (state, {scope, areas}) =>
        state.scope === scope ? {...state, areas: sortAreas(areas), loading: false, loaded: true} : state,
    ),
    on(AreaActions.loadAreasFailure, state => ({...state, loading: false})),

    on(AreaActions.createAreaSuccess, (state, {scope, area}) =>
        state.scope === scope ? {...state, areas: sortAreas([...state.areas, area])} : state,
    ),
    on(AreaActions.renameAreaSuccess, (state, {scope, area}) =>
        state.scope === scope
            ? {...state, areas: sortAreas(state.areas.map(a => (a.id === area.id ? area : a)))}
            : state,
    ),
    on(AreaActions.deleteAreaSuccess, (state, {scope, id}) =>
        state.scope === scope ? {...state, areas: state.areas.filter(a => a.id !== id)} : state,
    ),
);
