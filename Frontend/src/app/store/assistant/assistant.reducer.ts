import {createReducer, on} from "@ngrx/store";
import {AssistantSettings, ChatTemplate, ModelOption} from "../../models/assistant.model";
import {TenantScope} from "../../models/tenant.model";
import * as AssistantActions from "./assistant.actions";

/** One tenant's assistant at a time: the tenant admin's own, or the one the admin opened. */
export interface AssistantState {
    scope: TenantScope | null;
    settings: AssistantSettings | null;
    templates: ChatTemplate[];
    /** The chat models the tenant may use - for its default model. */
    models: ModelOption[];
    /** The image models the tenant may use - for the chat's image model. */
    imageModels: ModelOption[];
    loading: boolean;
    savingSettings: boolean;
}

export const initialState: AssistantState = {
    scope: null,
    settings: null,
    templates: [],
    models: [],
    imageModels: [],
    loading: false,
    savingSettings: false,
};

const byName = (a: ChatTemplate, b: ChatTemplate) => a.name.localeCompare(b.name, "de");

export const assistantReducer = createReducer(
    initialState,

    on(AssistantActions.loadAssistant, (state, {scope}) => ({
        ...state,
        scope,
        settings: state.scope === scope ? state.settings : null,
        templates: state.scope === scope ? state.templates : [],
        models: state.scope === scope ? state.models : [],
        imageModels: state.scope === scope ? state.imageModels : [],
        loading: true,
    })),
    // Results for another tenant (the admin opened another one's meanwhile) are ignored.
    on(AssistantActions.loadAssistantSuccess, (state, {scope, settings, templates, models, imageModels}) =>
        state.scope === scope ? {...state, settings, templates, models, imageModels, loading: false} : state,
    ),
    on(AssistantActions.loadAssistantFailure, state => ({...state, loading: false})),

    on(AssistantActions.saveSettings, state => ({...state, savingSettings: true})),
    on(AssistantActions.saveSettingsSuccess, (state, {scope, settings}) =>
        state.scope === scope ? {...state, settings, savingSettings: false} : {...state, savingSettings: false},
    ),
    on(AssistantActions.saveSettingsFailure, state => ({...state, savingSettings: false})),

    on(AssistantActions.createTemplateSuccess, (state, {scope, template}) =>
        state.scope === scope ? {...state, templates: [...state.templates, template].sort(byName)} : state,
    ),
    on(AssistantActions.updateTemplateSuccess, (state, {scope, template}) =>
        state.scope === scope
            ? {...state, templates: state.templates.map(t => (t.id === template.id ? template : t)).sort(byName)}
            : state,
    ),
    on(AssistantActions.deleteTemplateSuccess, (state, {scope, id}) =>
        state.scope === scope ? {...state, templates: state.templates.filter(t => t.id !== id)} : state,
    ),
);
