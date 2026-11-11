import * as LanguageActions from "./language.actions";
import {initialState, languageReducer} from "./language.reducer";

describe("language reducer", () => {
    it("starts in English", () => {
        expect(initialState.language).toBe("en");
    });

    it.each([
        [LanguageActions.useLanguage({language: "de"}), "de"],
        [LanguageActions.chooseLanguage({language: "de-AT"}), "de"],
        [LanguageActions.useLanguage({language: "fr"}), "en"],
    ])("speaks what %j asks for, as an offered language", (action, expected) => {
        expect(languageReducer({language: "de"}, action).language).toBe(expected);
    });
});
