import en from "../../i18n/en.json";
import de from "../../i18n/de.json";
import {DEFAULT_LANGUAGE, Language, LANGUAGES} from "../utils/language.util";

/** Every language file, by language - a new language is added here with its entry in LANGUAGES. */
const FILES: Record<Language, object> = {en, de};

/** "a.b.c" → text, for every text of a language file. */
function flatten(node: object, prefix = ""): Map<string, unknown> {
    const texts = new Map<string, unknown>();
    for (const [key, value] of Object.entries(node)) {
        const path = prefix + key;
        if (value !== null && typeof value === "object") {
            flatten(value, path + ".").forEach((text, nested) => texts.set(nested, text));
        } else {
            texts.set(path, value);
        }
    }
    return texts;
}

/** The names of a text's {{params}}, sorted. */
function paramsOf(text: unknown): string[] {
    return [...String(text).matchAll(/\{\{\s*(\w+)\s*}}/g)].map(match => match[1]).sort();
}

describe("language files", () => {
    const reference = flatten(FILES[DEFAULT_LANGUAGE]);

    it("has a file for every offered language", () => {
        expect(Object.keys(FILES).sort()).toEqual(LANGUAGES.map(language => language.value).sort());
    });

    it.each(LANGUAGES.map(language => language.value))("%s has exactly the keys of English", language => {
        const texts = flatten(FILES[language]);
        const missing = [...reference.keys()].filter(key => !texts.has(key));
        const extra = [...texts.keys()].filter(key => !reference.has(key));
        expect({missing, extra}).toEqual({missing: [], extra: []});
    });

    it.each(LANGUAGES.map(language => language.value))("%s has a non-empty text for every key", language => {
        const empty = [...flatten(FILES[language])]
            .filter(([, text]) => typeof text !== "string" || text.trim().length === 0)
            .map(([key]) => key);
        expect(empty).toEqual([]);
    });

    it.each(LANGUAGES.map(language => language.value))("%s uses the same {{params}} as English", language => {
        const texts = flatten(FILES[language]);
        const different = [...reference]
            .filter(([key, text]) => texts.has(key) && paramsOf(text).join() !== paramsOf(texts.get(key)).join())
            .map(([key]) => key);
        expect(different).toEqual([]);
    });
});
