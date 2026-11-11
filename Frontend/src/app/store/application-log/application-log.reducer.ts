import {createReducer, on} from "@ngrx/store";
import {ApplicationLogEntry, ApplicationLogFilter} from "../../models/application-log.model";
import * as ApplicationLogActions from "./application-log.actions";

export interface ApplicationLogState {
    filter: ApplicationLogFilter;
    entries: ApplicationLogEntry[];
    capacity: number;
    startedAt: string | null;
    loading: boolean;
    /** The daily report is being sent at the admin's request. */
    sending: boolean;
}

export const initialFilter: ApplicationLogFilter = {level: null, search: ""};

export const initialState: ApplicationLogState = {
    filter: initialFilter,
    entries: [],
    capacity: 0,
    startedAt: null,
    loading: false,
    sending: false,
};

export const applicationLogReducer = createReducer(
    initialState,
    on(ApplicationLogActions.loadEntries, (state, {filter}) => ({...state, filter, loading: true})),
    on(ApplicationLogActions.loadEntriesSuccess, (state, {response}) => ({
        ...state,
        entries: response.entries,
        capacity: response.capacity,
        startedAt: response.startedAt,
        loading: false,
    })),
    on(ApplicationLogActions.loadEntriesFailure, state => ({...state, loading: false})),
    on(ApplicationLogActions.sendDailyReport, state => ({...state, sending: true})),
    on(ApplicationLogActions.sendDailyReportSuccess, ApplicationLogActions.sendDailyReportFailure, state => ({
        ...state,
        sending: false,
    })),
);
