import {HttpErrorResponse} from "@angular/common/http";
import {backendMessage, extractErrorMessage, Translator} from "./http-error.util";

/** A translator over a flat table, writing params in like Transloco's {{name}}. */
function translatorOf(texts: Record<string, string>): Translator {
    return {
        has: key => key in texts,
        translate: (key, params = {}) =>
            (texts[key] ?? key).replace(/\{\{\s*(\w+)\s*}}/g, (_, name: string) => String(params[name] ?? "")),
    };
}

const translator = translatorOf({
    "errors.fallback": "Fehler",
    "errors.backend.user.usernameTaken": 'Der Benutzername "{{username}}" ist bereits vergeben',
});

describe("extractErrorMessage", () => {
    it("translates a code the language files know, with its params", () => {
        const error = new HttpErrorResponse({
            status: 409,
            error: {
                message: 'The username "maria" is already taken',
                code: "user.usernameTaken",
                params: {username: "maria"},
            },
        });
        expect(extractErrorMessage(error, "errors.fallback", translator)).toBe(
            'Der Benutzername "maria" ist bereits vergeben',
        );
    });

    it("shows the backend's message for a code it does not know, or none", () => {
        const unknownCode = new HttpErrorResponse({
            status: 409,
            error: {message: "customerId 2 already belongs to another tenant", code: "tenant.somethingNew"},
        });
        const noCode = new HttpErrorResponse({status: 409, error: {message: "An older backend's message"}});

        expect(extractErrorMessage(unknownCode, "errors.fallback", translator)).toBe(
            "customerId 2 already belongs to another tenant",
        );
        expect(extractErrorMessage(noCode, "errors.fallback", translator)).toBe("An older backend's message");
    });

    it("falls back to the caller's text - never Angular's English status line", () => {
        expect(
            extractErrorMessage(new HttpErrorResponse({status: 500, error: null}), "errors.fallback", translator),
        ).toBe("Fehler");
        expect(
            extractErrorMessage(
                new HttpErrorResponse({status: 400, error: {message: "  "}}),
                "errors.fallback",
                translator,
            ),
        ).toBe("Fehler");
        expect(extractErrorMessage("irgendwas", "errors.fallback", translator)).toBe("Fehler");
    });

    it("keeps the message of a plain Error", () => {
        expect(extractErrorMessage(new Error("Lokaler Fehler"), "errors.fallback", translator)).toBe("Lokaler Fehler");
    });
});

describe("backendMessage", () => {
    it("reads the chat stream's error event like an HTTP error body", () => {
        expect(backendMessage({message: "x", code: "user.usernameTaken", params: {username: "anna"}}, translator)).toBe(
            'Der Benutzername "anna" ist bereits vergeben',
        );
        expect(backendMessage({message: "The provider failed"}, translator)).toBe("The provider failed");
        expect(backendMessage({}, translator)).toBeNull();
        expect(backendMessage(null, translator)).toBeNull();
    });
});
