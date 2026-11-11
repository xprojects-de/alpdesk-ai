import {createReducer, on} from "@ngrx/store";
import {ProviderKey} from "../../models/ai.model";
import {ProviderKeyScope} from "../../services/provider-key.service";
import * as ProviderKeyActions from "./provider-key.actions";

/**
 * The admin's global keys or one tenant's own keys - the admin sees both (the page "Globale API-Keys" and each
 * tenant's dialog), so the slice holds one scope at a time: a load of another clears the keys, and a late result for
 * another scope is ignored - a tenant's dialog must never show another's keys.
 */
export interface ProviderKeyState {
    scope: ProviderKeyScope | null;
    keys: ProviderKey[];
    loading: boolean;
}

export const initialState: ProviderKeyState = {
    scope: null,
    keys: [],
    loading: false,
};

export const providerKeyReducer = createReducer(
    initialState,

    on(ProviderKeyActions.loadKeys, (state, {scope}) => ({
        scope,
        keys: state.scope === scope ? state.keys : [],
        loading: true,
    })),
    on(ProviderKeyActions.setKey, ProviderKeyActions.removeKey, state => ({...state, loading: true})),
    on(
        ProviderKeyActions.loadKeysFailure,
        ProviderKeyActions.setKeyFailure,
        ProviderKeyActions.removeKeyFailure,
        state => ({...state, loading: false}),
    ),
    on(ProviderKeyActions.loadKeysSuccess, (state, {scope, keys}) =>
        state.scope === scope ? {...state, keys, loading: false} : state,
    ),
    on(ProviderKeyActions.setKeySuccess, (state, {scope, key}) =>
        state.scope === scope
            ? {...state, keys: [...state.keys.filter(k => k.provider !== key.provider), key], loading: false}
            : {...state, loading: false},
    ),
    on(ProviderKeyActions.removeKeySuccess, (state, {scope, provider}) =>
        state.scope === scope
            ? {...state, keys: state.keys.filter(k => k.provider !== provider), loading: false}
            : {...state, loading: false},
    ),
);
