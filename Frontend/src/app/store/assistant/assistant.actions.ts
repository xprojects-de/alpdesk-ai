import {createAction, props} from "@ngrx/store";
import {AssistantSettings, ChatTemplate, ChatTemplateRequest, ModelOption} from "../../models/assistant.model";
import {TenantScope} from "../../models/tenant.model";

/** Loads the settings and the templates together. */
export const loadAssistant = createAction("[Assistant] Load", props<{scope: TenantScope}>());
export const loadAssistantSuccess = createAction(
    "[Assistant] Load Success",
    props<{
        scope: TenantScope;
        settings: AssistantSettings;
        templates: ChatTemplate[];
        models: ModelOption[];
        imageModels: ModelOption[];
    }>(),
);
export const loadAssistantFailure = createAction("[Assistant] Load Failure", props<{error: string}>());

export const saveSettings = createAction(
    "[Assistant] Save Settings",
    props<{scope: TenantScope; settings: AssistantSettings}>(),
);
export const saveSettingsSuccess = createAction(
    "[Assistant] Save Settings Success",
    props<{scope: TenantScope; settings: AssistantSettings}>(),
);
export const saveSettingsFailure = createAction("[Assistant] Save Settings Failure", props<{error: string}>());

export const createTemplate = createAction(
    "[Assistant] Create Template",
    props<{scope: TenantScope; template: ChatTemplateRequest}>(),
);
export const createTemplateSuccess = createAction(
    "[Assistant] Create Template Success",
    props<{scope: TenantScope; template: ChatTemplate}>(),
);
export const createTemplateFailure = createAction("[Assistant] Create Template Failure", props<{error: string}>());

export const updateTemplate = createAction(
    "[Assistant] Update Template",
    props<{scope: TenantScope; id: number; template: ChatTemplateRequest}>(),
);
export const updateTemplateSuccess = createAction(
    "[Assistant] Update Template Success",
    props<{scope: TenantScope; template: ChatTemplate}>(),
);
export const updateTemplateFailure = createAction("[Assistant] Update Template Failure", props<{error: string}>());

export const deleteTemplate = createAction("[Assistant] Delete Template", props<{scope: TenantScope; id: number}>());
export const deleteTemplateSuccess = createAction(
    "[Assistant] Delete Template Success",
    props<{scope: TenantScope; id: number}>(),
);
export const deleteTemplateFailure = createAction("[Assistant] Delete Template Failure", props<{error: string}>());
