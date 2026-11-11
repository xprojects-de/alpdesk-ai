import {createAction, props} from "@ngrx/store";
import {AiProvider, ProviderKey} from "../../models/ai.model";
import {ProviderKeyScope} from "../../services/provider-key.service";

export const loadKeys = createAction("[Provider Key] Load Keys", props<{scope: ProviderKeyScope}>());
export const loadKeysSuccess = createAction(
    "[Provider Key] Load Keys Success",
    props<{scope: ProviderKeyScope; keys: ProviderKey[]}>(),
);
export const loadKeysFailure = createAction("[Provider Key] Load Keys Failure", props<{error: string}>());

export const setKey = createAction(
    "[Provider Key] Set Key",
    props<{scope: ProviderKeyScope; provider: AiProvider; apiKey: string}>(),
);
export const setKeySuccess = createAction(
    "[Provider Key] Set Key Success",
    props<{scope: ProviderKeyScope; key: ProviderKey}>(),
);
export const setKeyFailure = createAction("[Provider Key] Set Key Failure", props<{error: string}>());

export const removeKey = createAction(
    "[Provider Key] Remove Key",
    props<{scope: ProviderKeyScope; provider: AiProvider}>(),
);
export const removeKeySuccess = createAction(
    "[Provider Key] Remove Key Success",
    props<{scope: ProviderKeyScope; provider: AiProvider}>(),
);
export const removeKeyFailure = createAction("[Provider Key] Remove Key Failure", props<{error: string}>());
