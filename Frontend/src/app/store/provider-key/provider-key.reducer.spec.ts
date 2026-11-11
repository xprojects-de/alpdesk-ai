import {ProviderKey} from "../../models/ai.model";
import * as ProviderKeyActions from "./provider-key.actions";
import {initialState, providerKeyReducer} from "./provider-key.reducer";

describe("providerKeyReducer", () => {
    const key = (provider: "OPENAI" | "GEMINI") => ({provider, keyHint: "…1234"}) as ProviderKey;

    it("loading another scope clears the keys - a tenant's dialog never shows another's", () => {
        const global = providerKeyReducer(
            providerKeyReducer(initialState, ProviderKeyActions.loadKeys({scope: "global"})),
            ProviderKeyActions.loadKeysSuccess({scope: "global", keys: [key("OPENAI")]}),
        );

        const tenant = providerKeyReducer(global, ProviderKeyActions.loadKeys({scope: 7}));

        expect(tenant.scope).toBe(7);
        expect(tenant.keys).toEqual([]);
        expect(providerKeyReducer(global, ProviderKeyActions.loadKeys({scope: "global"})).keys).toEqual([
            key("OPENAI"),
        ]);
    });

    it("ignores a late result of another scope", () => {
        const tenant = providerKeyReducer(initialState, ProviderKeyActions.loadKeys({scope: 7}));

        const late = [
            ProviderKeyActions.loadKeysSuccess({scope: 8, keys: [key("OPENAI")]}),
            ProviderKeyActions.setKeySuccess({scope: 8, key: key("GEMINI")}),
        ].reduce(providerKeyReducer, tenant);

        expect(late.keys).toEqual([]);
        expect(providerKeyReducer(late, ProviderKeyActions.setKeySuccess({scope: 7, key: key("GEMINI")})).keys).toEqual(
            [key("GEMINI")],
        );
    });
});
