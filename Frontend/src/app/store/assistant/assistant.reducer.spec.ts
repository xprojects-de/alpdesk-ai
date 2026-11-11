import {AssistantSettings, ChatTemplate} from "../../models/assistant.model";
import * as AssistantActions from "./assistant.actions";
import {assistantReducer, initialState} from "./assistant.reducer";

describe("assistantReducer", () => {
    const settings: AssistantSettings = {
        instructions: "",
        answerSource: "DOCUMENTS_ONLY",
        creativity: "MEDIUM",
        reasoning: "LOW",
        searchResults: 5,
        defaultModelId: null,
        imageModelId: null,
    };
    const template = (id: number, name: string) => ({id, name}) as ChatTemplate;

    it("drops another tenant's assistant when one is opened and ignores late results for it", () => {
        let state = assistantReducer(initialState, AssistantActions.loadAssistant({scope: 1}));
        state = assistantReducer(
            state,
            AssistantActions.loadAssistantSuccess({
                scope: 1,
                settings,
                templates: [template(1, "A")],
                models: [],
                imageModels: [],
            }),
        );
        expect(state.templates.length).toBe(1);

        state = assistantReducer(state, AssistantActions.loadAssistant({scope: 2}));
        expect(state.settings).toBeNull();
        expect(state.templates).toEqual([]);

        state = assistantReducer(
            state,
            AssistantActions.loadAssistantSuccess({
                scope: 1,
                settings,
                templates: [template(1, "A")],
                models: [],
                imageModels: [],
            }),
        );
        expect(state.templates).toEqual([]);
    });

    it("keeps the templates sorted by name through create, update and delete", () => {
        let state = assistantReducer(
            assistantReducer(initialState, AssistantActions.loadAssistant({scope: "self"})),
            AssistantActions.loadAssistantSuccess({
                scope: "self",
                settings,
                templates: [template(1, "Planer")],
                models: [],
                imageModels: [],
            }),
        );
        state = assistantReducer(
            state,
            AssistantActions.createTemplateSuccess({scope: "self", template: template(2, "Alpha")}),
        );
        expect(state.templates.map(t => t.name)).toEqual(["Alpha", "Planer"]);

        state = assistantReducer(
            state,
            AssistantActions.updateTemplateSuccess({scope: "self", template: template(2, "Zeta")}),
        );
        expect(state.templates.map(t => t.name)).toEqual(["Planer", "Zeta"]);

        state = assistantReducer(state, AssistantActions.deleteTemplateSuccess({scope: "self", id: 1}));
        expect(state.templates.map(t => t.id)).toEqual([2]);
    });

    it("marks the settings saving until they are answered", () => {
        const saving = assistantReducer(initialState, AssistantActions.saveSettings({scope: "self", settings}));
        expect(saving.savingSettings).toBe(true);
        expect(assistantReducer(saving, AssistantActions.saveSettingsFailure({error: "x"})).savingSettings).toBe(false);
    });

    it("ignores an assistant answered after the logout, before the next login opened one", () => {
        const late = AssistantActions.loadAssistantSuccess({
            scope: "self",
            settings,
            templates: [template(1, "A")],
            models: [],
            imageModels: [],
        });
        expect(assistantReducer(initialState, late)).toBe(initialState);
    });
});
