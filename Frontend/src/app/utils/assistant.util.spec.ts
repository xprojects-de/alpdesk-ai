import {ChatTemplate} from "../models/assistant.model";
import {
    buildAssistantSettings,
    buildChatTemplateRequest,
    isSearchResults,
    imageModelHint,
    noImageModelLabel,
    templateSummary,
} from "./assistant.util";

describe("assistant.util", () => {
    it("accepts 3 to 20 search results, whole numbers only", () => {
        expect(isSearchResults(3)).toBe(true);
        expect(isSearchResults(20)).toBe(true);
        expect(isSearchResults(2)).toBe(false);
        expect(isSearchResults(21)).toBe(false);
        expect(isSearchResults(5.5)).toBe(false);
        expect(isSearchResults(null)).toBe(false);
        expect(isSearchResults("")).toBe(false);
    });

    it("builds the settings with trimmed instructions and a number", () => {
        expect(
            buildAssistantSettings({
                instructions: "  Du hilfst den Trainern. ",
                answerSource: "DOCUMENTS_FIRST",
                creativity: "HIGH",
                reasoning: "OFF",
                searchResults: "8" as unknown as number,
                defaultModelId: 3,
                imageModelId: 7,
            }),
        ).toEqual({
            instructions: "Du hilfst den Trainern.",
            answerSource: "DOCUMENTS_FIRST",
            creativity: "HIGH",
            reasoning: "OFF",
            searchResults: 8,
            defaultModelId: 3,
            imageModelId: 7,
        });
    });

    it("names the image model used when none is chosen: the only one, else none", () => {
        expect(noImageModelLabel([{id: 7, displayName: "Nano Banana"}])).toEqual({
            key: "assistant.settings.imageModelAuto",
            params: {model: "Nano Banana"},
        });
        expect(
            noImageModelLabel([
                {id: 7, displayName: "Nano Banana"},
                {id: 8, displayName: "Nano Banana Pro"},
            ]),
        ).toEqual({key: "assistant.settings.none"});
        expect(noImageModelLabel([])).toEqual({key: "assistant.settings.none"});
    });

    it("says in the hint which image model the chat uses without a choice", () => {
        const one = [{id: 7, displayName: "Nano Banana"}];
        expect(imageModelHint(one, null)).toEqual({
            key: "assistant.settings.imageModelHintAuto",
            params: {model: "Nano Banana"},
        });
        expect(imageModelHint([...one, {id: 8, displayName: "Pro"}], null)).toEqual({
            key: "assistant.settings.imageModelHintNone",
        });
        expect(imageModelHint(one, 7)).toEqual({key: "assistant.settings.imageModelHintChosen"});
    });

    it("sends a template's empty settings as null - the tenant's apply", () => {
        expect(
            buildChatTemplateRequest({
                name: " Planer ",
                instructions: " Als Wochenplan. ",
                answerSource: "",
                creativity: "LOW",
                reasoning: "",
                searchResults: null,
            }),
        ).toEqual({
            name: "Planer",
            instructions: "Als Wochenplan.",
            answerSource: null,
            creativity: "LOW",
            reasoning: null,
            searchResults: null,
        });
        expect(
            buildChatTemplateRequest({
                name: "a",
                instructions: "",
                answerSource: "",
                creativity: "",
                reasoning: "",
                searchResults: 10,
            }).searchResults,
        ).toBe(10);
    });

    it("summarises what a template sets itself", () => {
        const template = (changes: Partial<ChatTemplate>) =>
            ({answerSource: null, creativity: null, reasoning: null, searchResults: null, ...changes}) as ChatTemplate;
        expect(templateSummary(template({}))).toEqual([{key: "assistant.templates.summary.default"}]);
        expect(
            templateSummary(template({answerSource: "DOCUMENTS_ONLY", reasoning: "HIGH", searchResults: 10})),
        ).toEqual([
            {key: "assistant.answerSource.documentsOnly"},
            {key: "assistant.templates.summary.reasoning", valueKey: "assistant.reasoning.high"},
            {key: "assistant.templates.summary.searchResults", params: {count: 10}},
        ]);
    });
});
