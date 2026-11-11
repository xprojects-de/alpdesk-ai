import {createAction, props} from "@ngrx/store";
import {UsageRow} from "../../models/ai.model";
import {UsageScope} from "../../services/usage.service";

/** `month` as YYYY-MM. */
export const loadUsage = createAction("[Usage] Load Usage", props<{scope: UsageScope; month: string}>());
export const loadUsageSuccess = createAction("[Usage] Load Usage Success", props<{month: string; rows: UsageRow[]}>());
export const loadUsageFailure = createAction("[Usage] Load Usage Failure", props<{error: string}>());
