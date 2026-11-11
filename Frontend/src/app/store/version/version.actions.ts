import {createAction, props} from "@ngrx/store";
import {VersionInfo} from "../../models/version.model";

// Load version info
export const loadVersion = createAction("[Version] Load Version");
export const loadVersionSuccess = createAction("[Version] Load Version Success", props<{version: VersionInfo}>());
export const loadVersionFailure = createAction("[Version] Load Version Failure", props<{error: string}>());
