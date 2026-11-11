import {HttpErrorResponse} from "@angular/common/http";

/** What the error messages need of the translations - a plain interface, so this util is specced without Transloco. */
export interface Translator {
    translate(key: string, params?: Record<string, unknown>): string;
    /** Whether the active language has a text for the key. */
    has(key: string): boolean;
}

/** Where the texts for the backend's error codes live in the language files: errors.backend.<code>. */
export const BACKEND_ERROR_PREFIX = "errors.backend.";

/**
 * Extracts a user-facing message from a failed HTTP call, in the user's language.
 *
 * The backend's JSON error body carries `{message, code, params}` (docs/architecture/i18n.md "Error codes"): a code
 * the language files know wins, with its params; else the backend's English message; else the caller's fallback key.
 *
 * Deliberately NOT falling back to `HttpErrorResponse.message`: that is Angular's own generic English string (e.g.
 * "Http failure response for .../teams: 409 Conflict"), and since it is always set it would shadow every fallback -
 * including for blob downloads, where `error.error` is a Blob and never carries a message.
 */
export function extractErrorMessage(error: unknown, fallbackKey: string, translator: Translator): string {
    if (error instanceof HttpErrorResponse) {
        return backendMessage(error.error, translator) ?? translator.translate(fallbackKey);
    }
    if (error instanceof Error && error.message) {
        return error.message;
    }
    return translator.translate(fallbackKey);
}

/**
 * The text of a backend error body (an HTTP error's, or the chat stream's "error" event): the code's translation,
 * else the English message; null when the body carries neither.
 */
export function backendMessage(body: unknown, translator: Translator): string | null {
    const {message, code, params} = (body ?? {}) as {message?: unknown; code?: unknown; params?: unknown};
    if (typeof code === "string" && code.length > 0 && translator.has(BACKEND_ERROR_PREFIX + code)) {
        return translator.translate(BACKEND_ERROR_PREFIX + code, paramsOf(params));
    }
    if (typeof message === "string" && message.trim().length > 0) {
        return message;
    }
    return null;
}

function paramsOf(params: unknown): Record<string, unknown> {
    return params !== null && typeof params === "object" ? (params as Record<string, unknown>) : {};
}
