import {inject, Injectable} from "@angular/core";
import {TranslocoService} from "@jsverse/transloco";
import {backendMessage, extractErrorMessage, Translator} from "../utils/http-error.util";

/**
 * The effects' error messages in the user's language: written when the failure happens, so state keeps a plain
 * string (a message shown while the language is switched stays in the old one).
 */
@Injectable({providedIn: "root"})
export class ErrorMessages {
    private transloco = inject(TranslocoService);

    private readonly translator: Translator = {
        translate: (key, params) => this.transloco.translate(key, params),
        has: key => {
            const translation = this.transloco.getTranslation(this.transloco.getActiveLang());
            return typeof translation[key] === "string";
        },
    };

    /** A failed HTTP call's message: the backend's code translated, its message, or the fallback key's text. */
    of(error: unknown, fallbackKey: string): string {
        return extractErrorMessage(error, fallbackKey, this.translator);
    }

    /** A backend error body that did not come as an HTTP error (the chat stream's "error" event). */
    ofBody(body: unknown, fallbackKey: string): string {
        return backendMessage(body, this.translator) ?? this.transloco.translate(fallbackKey);
    }
}
