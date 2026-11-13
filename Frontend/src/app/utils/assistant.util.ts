import {
    AnswerSource,
    ANSWER_SOURCE_LABELS,
    AssistantSettings,
    ChatTemplate,
    ChatTemplateRequest,
    Creativity,
    CREATIVITY_LABELS,
    MAX_SEARCH_RESULTS,
    MIN_SEARCH_RESULTS,
    ModelOption,
    Reasoning,
    REASONING_LABELS,
} from "../models/assistant.model";

/** The settings form: like the request, the number field may be empty while typing. */
export interface AssistantSettingsFormValue {
    instructions: string;
    answerSource: AnswerSource;
    creativity: Creativity;
    reasoning: Reasoning;
    searchResults: number | null;
    defaultModelId: number | null;
    imageModelId: number | null;
}

/** The template form: "" in a select or an empty number = the tenant's setting. */
export interface ChatTemplateFormValue {
    name: string;
    instructions: string;
    description: string;
    answerSource: AnswerSource | "";
    creativity: Creativity | "";
    reasoning: Reasoning | "";
    searchResults: number | null;
}

/** Like the backend: 3 to 20 passages per search. */
export function isSearchResults(value: number | null | string): boolean {
    const count = Number(value);
    return (
        value !== null &&
        `${value}` !== "" &&
        Number.isInteger(count) &&
        count >= MIN_SEARCH_RESULTS &&
        count <= MAX_SEARCH_RESULTS
    );
}

export function buildAssistantSettings(value: AssistantSettingsFormValue): AssistantSettings {
    return {
        instructions: value.instructions.trim(),
        answerSource: value.answerSource,
        creativity: value.creativity,
        reasoning: value.reasoning,
        searchResults: Number(value.searchResults),
        defaultModelId: value.defaultModelId,
        imageModelId: value.imageModelId,
    };
}

/** A text to show: its translation key and the key's parameters - the template translates it. */
export interface LabelKey {
    key: string;
    params?: Record<string, string | number>;
}

/**
 * What "none chosen" means for the image model, like the backend: the only image model the tenant may use is used
 * without being chosen; with several, none until one is chosen. The select shows no text for none - its hint says it.
 */
export function noImageModelLabel(imageModels: ModelOption[]): LabelKey {
    return imageModels.length === 1
        ? {key: "assistant.settings.imageModelAuto", params: {model: imageModels[0].displayName}}
        : {key: "assistant.settings.none"};
}

export function imageModelHint(imageModels: ModelOption[], chosenId: number | null): LabelKey {
    if (chosenId !== null) {
        return {key: "assistant.settings.imageModelHintChosen"};
    }
    return imageModels.length === 1
        ? {key: "assistant.settings.imageModelHintAuto", params: {model: imageModels[0].displayName}}
        : {key: "assistant.settings.imageModelHintNone"};
}

/** The template request: trimmed texts, "Standard" (empty) as null - the tenant's setting applies; no description null. */
export function buildChatTemplateRequest(value: ChatTemplateFormValue): ChatTemplateRequest {
    const empty = value.searchResults === null || `${value.searchResults}` === "";
    return {
        name: value.name.trim(),
        instructions: value.instructions.trim(),
        description: value.description.trim() || null,
        answerSource: value.answerSource || null,
        creativity: value.creativity || null,
        reasoning: value.reasoning || null,
        searchResults: empty ? null : Number(value.searchResults),
    };
}

/** One part of a template's summary; valueKey: a setting's label, translated into the part's {{value}}. */
export interface SummaryPart extends LabelKey {
    valueKey?: string;
}

/** What a template sets itself, for the list - "like the defaults" when nothing. */
export function templateSummary(template: ChatTemplate): SummaryPart[] {
    const parts: SummaryPart[] = [];
    if (template.answerSource) {
        parts.push({key: ANSWER_SOURCE_LABELS[template.answerSource]});
    }
    if (template.creativity) {
        parts.push({key: "assistant.templates.summary.creativity", valueKey: CREATIVITY_LABELS[template.creativity]});
    }
    if (template.reasoning) {
        parts.push({key: "assistant.templates.summary.reasoning", valueKey: REASONING_LABELS[template.reasoning]});
    }
    if (template.searchResults !== null) {
        parts.push({key: "assistant.templates.summary.searchResults", params: {count: template.searchResults}});
    }
    return parts.length ? parts : [{key: "assistant.templates.summary.default"}];
}
