import {createReducer, on} from "@ngrx/store";
import {UsageRow} from "../../models/ai.model";
import * as UsageActions from "./usage.actions";

export interface UsageState {
    /** The month shown (YYYY-MM); null until the page opened once. */
    month: string | null;
    rows: UsageRow[];
    loading: boolean;
}

export const initialState: UsageState = {
    month: null,
    rows: [],
    loading: false,
};

export const usageReducer = createReducer(
    initialState,

    on(UsageActions.loadUsage, (state, {month}) => ({
        month,
        rows: state.month === month ? state.rows : [],
        loading: true,
    })),
    // switchMap drops a superseded request, but a result for another month is still not shown.
    on(UsageActions.loadUsageSuccess, (state, {month, rows}) =>
        state.month === month ? {...state, rows, loading: false} : state,
    ),
    on(UsageActions.loadUsageFailure, state => ({...state, loading: false})),
);
