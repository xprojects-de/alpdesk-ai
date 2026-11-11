import {createReducer, on} from "@ngrx/store";
import {Account} from "../../models/account.model";
import * as AccountActions from "./account.actions";

export interface AccountState {
    account: Account | null;
    loading: boolean;
    /** A password change is running. */
    saving: boolean;
}

export const initialState: AccountState = {
    account: null,
    loading: false,
    saving: false,
};

export const accountReducer = createReducer(
    initialState,

    on(AccountActions.loadAccount, state => ({...state, loading: true})),
    on(AccountActions.loadAccountSuccess, (state, {account}) => ({...state, account, loading: false})),
    on(AccountActions.loadAccountFailure, state => ({...state, loading: false})),

    on(AccountActions.changePassword, state => ({...state, saving: true})),
    on(AccountActions.changePasswordSuccess, AccountActions.changePasswordFailure, state => ({
        ...state,
        saving: false,
    })),
);
