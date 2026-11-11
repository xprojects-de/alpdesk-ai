import {createAction, props} from "@ngrx/store";
import {AiModel, AiModelRequest} from "../../models/ai.model";

export const loadModels = createAction("[AI Model] Load Models");
export const loadModelsSuccess = createAction("[AI Model] Load Models Success", props<{models: AiModel[]}>());
export const loadModelsFailure = createAction("[AI Model] Load Models Failure", props<{error: string}>());

export const createModel = createAction("[AI Model] Create Model", props<{model: AiModelRequest}>());
export const createModelSuccess = createAction("[AI Model] Create Model Success", props<{model: AiModel}>());
export const createModelFailure = createAction("[AI Model] Create Model Failure", props<{error: string}>());

export const updateModel = createAction("[AI Model] Update Model", props<{id: number; model: AiModelRequest}>());
export const updateModelSuccess = createAction("[AI Model] Update Model Success", props<{model: AiModel}>());
export const updateModelFailure = createAction("[AI Model] Update Model Failure", props<{error: string}>());

export const deleteModel = createAction("[AI Model] Delete Model", props<{id: number}>());
export const deleteModelSuccess = createAction("[AI Model] Delete Model Success", props<{id: number}>());
export const deleteModelFailure = createAction("[AI Model] Delete Model Failure", props<{error: string}>());
