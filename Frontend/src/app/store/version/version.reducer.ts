import {createReducer, on} from "@ngrx/store";
import {VersionInfo} from "../../models/version.model";
import * as VersionActions from "./version.actions";

export interface VersionState {
    version: VersionInfo | null;
}

export const initialState: VersionState = {
    version: null,
};

export const versionReducer = createReducer(
    initialState,
    on(VersionActions.loadVersionSuccess, (state, {version}) => ({...state, version})),
);
