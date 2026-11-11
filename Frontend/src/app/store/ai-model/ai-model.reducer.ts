import {createReducer, on} from "@ngrx/store";
import {AiModel} from "../../models/ai.model";
import * as AiModelActions from "./ai-model.actions";

export interface AiModelState {
    models: AiModel[];
    loading: boolean;
}

export const initialState: AiModelState = {
    models: [],
    loading: false,
};

const byName = (a: AiModel, b: AiModel) => a.displayName.localeCompare(b.displayName, "de");

export const aiModelReducer = createReducer(
    initialState,

    on(
        AiModelActions.loadModels,
        AiModelActions.createModel,
        AiModelActions.updateModel,
        AiModelActions.deleteModel,
        state => ({...state, loading: true}),
    ),
    on(
        AiModelActions.loadModelsFailure,
        AiModelActions.createModelFailure,
        AiModelActions.updateModelFailure,
        AiModelActions.deleteModelFailure,
        state => ({...state, loading: false}),
    ),
    on(AiModelActions.loadModelsSuccess, (state, {models}) => ({
        ...state,
        models: [...models].sort(byName),
        loading: false,
    })),
    on(AiModelActions.createModelSuccess, (state, {model}) => ({
        ...state,
        models: [...state.models, model].sort(byName),
        loading: false,
    })),
    on(AiModelActions.updateModelSuccess, (state, {model}) => ({
        ...state,
        models: state.models.map(m => (m.id === model.id ? model : m)).sort(byName),
        loading: false,
    })),
    on(AiModelActions.deleteModelSuccess, (state, {id}) => ({
        ...state,
        models: state.models.filter(m => m.id !== id),
        loading: false,
    })),
);
