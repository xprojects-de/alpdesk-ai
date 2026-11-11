import {createAction, props} from "@ngrx/store";

/** Speak this language - at start and after a logout (the browser's), when the account is loaded (the user's). */
export const useLanguage = createAction("[Language] Use Language", props<{language: string}>());

/** The person's choice in the shell's menu: spoken at once, kept on a tenant user's account. */
export const chooseLanguage = createAction("[Language] Choose Language", props<{language: string}>());
/** userId: the tenant user whose account keeps it now - null for the admin (no account). */
export const chooseLanguageSuccess = createAction(
    "[Language] Choose Language Success",
    props<{userId: number | null; language: string}>(),
);
export const chooseLanguageFailure = createAction("[Language] Choose Language Failure", props<{error: string}>());
