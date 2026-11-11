import {inject, Signal} from "@angular/core";
import {toSignal} from "@angular/core/rxjs-interop";
import {TranslocoLocaleService} from "@jsverse/transloco-locale";

/**
 * The locale of the active language ("en-GB", "de-DE") as a signal - for a computed value that formats numbers or
 * dates in code, so it is written again when the language is switched. Templates use transloco-locale's pipes
 * (translocoDate, translocoDecimal) instead. Call it in an injection context (a field initializer).
 */
export function injectLocale(): Signal<string> {
    const locales = inject(TranslocoLocaleService);
    return toSignal(locales.localeChanges$, {initialValue: locales.getLocale()});
}
