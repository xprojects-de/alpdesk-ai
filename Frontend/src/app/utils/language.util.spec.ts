import {applyLanguage, browserLanguage, DEFAULT_LANGUAGE, LANGUAGES, languageOf, localeOf} from "./language.util";

describe("language util", () => {
    it("offers English first, as the default", () => {
        expect(DEFAULT_LANGUAGE).toBe("en");
        expect(LANGUAGES[0].value).toBe("en");
    });

    it.each([
        ["en", "en"],
        ["de", "de"],
        ["de-AT", "de"],
        ["DE", "de"],
        ["en-US", "en"],
        ["fr", "en"],
        ["", "en"],
        [null, "en"],
        [undefined, "en"],
    ])("takes %s as %s", (tag, expected) => {
        expect(languageOf(tag)).toBe(expected);
    });

    it.each([
        [["de-DE", "en"], "de"],
        [["fr-FR", "de"], "de"],
        [["en-US", "de"], "en"],
        [["fr-FR", "it"], "en"],
        [[], "en"],
    ])("takes the browser's first offered language %j → %s", (browser, expected) => {
        expect(browserLanguage(browser)).toBe(expected);
    });

    it("writes English in the British way and German in the German", () => {
        expect(localeOf("en")).toBe("en-GB");
        expect(localeOf("de")).toBe("de-DE");
    });

    it("sets the page's lang", () => {
        const root = document.createElement("html");
        applyLanguage(root, "de");
        expect(root.lang).toBe("de");
    });
});
