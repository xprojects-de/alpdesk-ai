import {createAction, props} from "@ngrx/store";
import {Account, PasswordChangeRequest} from "../../models/account.model";

export const loadAccount = createAction("[Account] Load Account");
export const loadAccountSuccess = createAction("[Account] Load Account Success", props<{account: Account}>());
export const loadAccountFailure = createAction("[Account] Load Account Failure", props<{error: string}>());

export const changePassword = createAction("[Account] Change Password", props<{request: PasswordChangeRequest}>());
export const changePasswordSuccess = createAction("[Account] Change Password Success");
export const changePasswordFailure = createAction("[Account] Change Password Failure", props<{error: string}>());
