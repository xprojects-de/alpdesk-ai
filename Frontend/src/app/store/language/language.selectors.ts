import {createFeatureSelector, createSelector} from "@ngrx/store";
import {LanguageState} from "./language.reducer";

export const selectLanguageState = createFeatureSelector<LanguageState>("language");

export const selectLanguage = createSelector(selectLanguageState, state => state.language);
