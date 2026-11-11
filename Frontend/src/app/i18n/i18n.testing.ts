import {ModuleWithProviders} from "@angular/core";
import {TRANSLOCO_TRANSPILER, TranslocoTestingModule} from "@jsverse/transloco";
import en from "../../i18n/en.json";
import de from "../../i18n/de.json";
import {Language, LANGUAGES} from "../utils/language.util";
import {SinglePassTranspiler} from "./single-pass-transpiler";

/** Transloco for specs: the real language files, loaded at once, speaking the given language, the app's transpiler. */
export function translocoTesting(language: Language = "en"): ModuleWithProviders<TranslocoTestingModule> {
    const testing = TranslocoTestingModule.forRoot({
        langs: {en, de},
        translocoConfig: {
            availableLangs: LANGUAGES.map(entry => entry.value),
            defaultLang: language,
            missingHandler: {logMissingKey: false},
        },
        preloadLangs: true,
    });
    return {
        ...testing,
        providers: [...(testing.providers ?? []), {provide: TRANSLOCO_TRANSPILER, useClass: SinglePassTranspiler}],
    };
}
