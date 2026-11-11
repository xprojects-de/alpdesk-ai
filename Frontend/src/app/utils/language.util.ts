/**
 * The languages of the frontend (docs/architecture/i18n.md). This list is the only place that knows them - the backend
 * stores a user's tag without a list. A new language is a file in src/i18n/ plus an entry here (and in
 * TRANSLATIONS of i18n.loader.ts); i18n.spec.ts checks that its file has every key of English.
 */
export const LANGUAGES = [
    {value: "en", label: "English", locale: "en-GB"},
    {value: "de", label: "Deutsch", locale: "de-DE"},
] as const;

export type Language = (typeof LANGUAGES)[number]["value"];

/** The reference language: its file is written first, and it is what an unknown tag or browser language gets. */
export const DEFAULT_LANGUAGE: Language = "en";

/**
 * The offered language for a tag: itself, else its primary language ("de-AT" → "de"), else the default - also for a
 * language a user chose that is no longer offered.
 */
export function languageOf(tag: string | null | undefined): Language {
    const normalized = (tag ?? "").trim().toLowerCase();
    const primary = normalized.split("-")[0];
    return (
        LANGUAGES.find(language => language.value === normalized)?.value ??
        LANGUAGES.find(language => language.value === primary)?.value ??
        DEFAULT_LANGUAGE
    );
}

/**
 * The language before a login and for the admin (it has no account): the browser's first offered language, else
 * English. Nothing is remembered in the browser - the account keeps a tenant user's language.
 */
export function browserLanguage(browserLanguages: readonly string[]): Language {
    for (const tag of browserLanguages) {
        const primary = tag.trim().toLowerCase().split("-")[0];
        const offered = LANGUAGES.find(language => language.value === primary);
        if (offered) {
            return offered.value;
        }
    }
    return DEFAULT_LANGUAGE;
}

/** The locale dates and numbers are written in (en-GB: day before month, 24 hours). */
export function localeOf(language: Language): string {
    return LANGUAGES.find(entry => entry.value === language)?.locale ?? "en-GB";
}

/** Screen readers and the browser's hyphenation follow the page's lang. */
export function applyLanguage(root: HTMLElement, language: Language): void {
    root.lang = language;
}
