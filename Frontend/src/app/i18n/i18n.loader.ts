import {Injectable} from "@angular/core";
import {Translation, TranslocoLoader} from "@jsverse/transloco";
import {from, Observable} from "rxjs";
import {Language, languageOf} from "../utils/language.util";

/**
 * The language files, bundled as chunks of their own: loaded on demand, cached by their hashed names, and served under
 * a context path like the rest of the build - no request to a path of their own.
 */
const TRANSLATIONS: Record<Language, () => Promise<{default: Translation}>> = {
    en: () => import("../../i18n/en.json"),
    de: () => import("../../i18n/de.json"),
};

@Injectable({providedIn: "root"})
export class TranslationLoader implements TranslocoLoader {
    getTranslation(language: string): Observable<Translation> {
        return from(TRANSLATIONS[languageOf(language)]().then(module => module.default));
    }
}
