import {createAction, props} from "@ngrx/store";
import {ApplicationLogFilter, ApplicationLogResponse} from "../../models/application-log.model";

export const loadEntries = createAction("[ApplicationLog] Load Entries", props<{filter: ApplicationLogFilter}>());
export const loadEntriesSuccess = createAction(
    "[ApplicationLog] Load Entries Success",
    props<{response: ApplicationLogResponse}>(),
);
export const loadEntriesFailure = createAction("[ApplicationLog] Load Entries Failure", props<{error: string}>());

/** The admin's test of the mail settings: the daily report now, to its recipients. */
export const sendDailyReport = createAction("[ApplicationLog] Send Daily Report");
export const sendDailyReportSuccess = createAction(
    "[ApplicationLog] Send Daily Report Success",
    props<{recipients: number}>(),
);
export const sendDailyReportFailure = createAction(
    "[ApplicationLog] Send Daily Report Failure",
    props<{error: string}>(),
);
