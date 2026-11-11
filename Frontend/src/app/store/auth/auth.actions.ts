import {createAction, props} from "@ngrx/store";
import {LoginRequest, LoginResponse, TenantChoice} from "../../models/auth.model";

// Login
export const login = createAction("[Auth] Login", props<{credentials: LoginRequest}>());

export const loginSuccess = createAction("[Auth] Login Success", props<{response: LoginResponse}>());

export const loginFailure = createAction("[Auth] Login Failure", props<{error: string}>());

// Sign-in through a WAM proxy
export const loadWamStatus = createAction("[Auth] Load WAM Status");
export const loadWamStatusSuccess = createAction(
    "[Auth] Load WAM Status Success",
    props<{enabled: boolean; groupsChecked: boolean}>(),
);
/** tenantId: the tenant the person chose - left out, the one chosen last in this browser is tried first. */
export const wamSignIn = createAction("[Auth] WAM Sign In", (props: {tenantId?: number} = {}) => props);
/** The person has a user in several tenants: the login page lets it choose. */
export const wamChoose = createAction("[Auth] WAM Choose", props<{tenants: TenantChoice[]}>());
/** The backend answered 409: the person waits for the admin or the tenant admin to approve them. */
export const wamWaiting = createAction("[Auth] WAM Waiting");
export const wamSignInFailure = createAction("[Auth] WAM Sign In Failure", props<{error: string}>());

// The switch to the person's user in another tenant (user menu)
/** password: the target user's - asked without WAM only. */
export const switchTenant = createAction("[Auth] Switch Tenant", props<{tenant: TenantChoice; password?: string}>());
/** Every slice is reset like at a logout (logout.meta-reducer.ts), then the app starts anew with the new token. */
export const switchTenantSuccess = createAction("[Auth] Switch Tenant Success", props<{response: LoginResponse}>());
export const switchTenantFailure = createAction("[Auth] Switch Tenant Failure", props<{error: string}>());

// Logout
/** byUser: the user clicked "Abmelden" - with WAM the login page then waits instead of signing in again at once. */
export const logout = createAction("[Auth] Logout", (props: {byUser?: boolean} = {}) => props);

export const logoutSuccess = createAction("[Auth] Logout Success");

// Check Auth
export const checkAuth = createAction("[Auth] Check Auth");

export const checkAuthSuccess = createAction(
    "[Auth] Check Auth Success",
    props<{token: string; username: string; roles: string[]}>(),
);

export const checkAuthFailure = createAction("[Auth] Check Auth Failure");
