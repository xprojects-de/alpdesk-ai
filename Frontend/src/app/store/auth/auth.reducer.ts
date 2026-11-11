import {createReducer, on} from "@ngrx/store";
import {AuthState} from "../../models/auth.model";
import * as AuthActions from "./auth.actions";

export const initialState: AuthState = {
    token: null,
    username: null,
    roles: [],
    isAuthenticated: false,
    loading: false,
    error: null,
    wamEnabled: false,
    wamGroupsChecked: false,
    wamWaiting: false,
    wamError: null,
    wamChoice: null,
    switchError: null,
};

export const authReducer = createReducer(
    initialState,

    // Login
    on(AuthActions.login, state => ({
        ...state,
        loading: true,
        error: null,
    })),
    on(AuthActions.loginSuccess, AuthActions.switchTenantSuccess, (state, {response}) => ({
        ...state,
        wamWaiting: false,
        wamChoice: null,
        switchError: null,
        token: response.access_token || response.token || null,
        username: response.username || null,
        roles: response.roles ?? [],
        isAuthenticated: true,
        loading: false,
        error: null,
    })),
    on(AuthActions.loginFailure, (state, {error}) => ({
        ...state,
        loading: false,
        error,
        isAuthenticated: false,
        token: null,
        username: null,
        roles: [],
    })),

    // Sign-in through a WAM proxy
    on(AuthActions.loadWamStatusSuccess, (state, {enabled, groupsChecked}) => ({
        ...state,
        wamEnabled: enabled,
        wamGroupsChecked: groupsChecked,
    })),
    on(AuthActions.wamSignIn, state => ({...state, loading: true, wamError: null, wamWaiting: false, wamChoice: null})),
    on(AuthActions.wamChoose, (state, {tenants}) => ({...state, loading: false, wamChoice: tenants})),
    on(AuthActions.wamWaiting, state => ({...state, loading: false, wamWaiting: true})),
    on(AuthActions.wamSignInFailure, (state, {error}) => ({...state, loading: false, wamError: error})),

    // The switch to another tenant
    on(AuthActions.switchTenant, state => ({...state, switchError: null})),
    on(AuthActions.switchTenantFailure, (state, {error}) => ({...state, switchError: error})),

    // Logout
    on(AuthActions.logout, state => ({
        ...state,
        loading: true,
    })),
    on(AuthActions.logoutSuccess, () => ({
        ...initialState,
    })),

    // Check Auth
    on(AuthActions.checkAuth, state => ({
        ...state,
        loading: true,
    })),
    on(AuthActions.checkAuthSuccess, (state, {token, username, roles}) => ({
        ...state,
        token,
        username,
        roles,
        isAuthenticated: true,
        loading: false,
    })),
    on(AuthActions.checkAuthFailure, () => ({
        ...initialState,
        loading: false,
    })),
);
