import {createReducer, on} from "@ngrx/store";
import {DEFAULT_LANGUAGE, Language, languageOf} from "../../utils/language.util";
import * as LanguageActions from "./language.actions";

/** The language the frontend speaks - the browser's again after a logout (LanguageEffects). */
export interface LanguageState {
    language: Language;
}

export const initialState: LanguageState = {
    language: DEFAULT_LANGUAGE,
};

export const languageReducer = createReducer(
    initialState,
    on(LanguageActions.useLanguage, LanguageActions.chooseLanguage, (state, {language}) => ({
        ...state,
        language: languageOf(language),
    })),
);
