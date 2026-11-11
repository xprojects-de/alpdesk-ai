/** What the assistant answers from - a rule of its instructions. */
export type AnswerSource = "DOCUMENTS_ONLY" | "DOCUMENTS_FIRST";
/** How freely the model writes (its temperature). */
export type Creativity = "LOW" | "MEDIUM" | "HIGH";
/** How much the model thinks before answering. */
export type Reasoning = "OFF" | "LOW" | "MEDIUM" | "HIGH";

/** The translation keys of the labels - the template translates them. */
export const ANSWER_SOURCE_LABELS: Record<AnswerSource, string> = {
    DOCUMENTS_ONLY: "assistant.answerSource.documentsOnly",
    DOCUMENTS_FIRST: "assistant.answerSource.documentsFirst",
};

export const CREATIVITY_LABELS: Record<Creativity, string> = {
    LOW: "assistant.creativity.low",
    MEDIUM: "assistant.creativity.medium",
    HIGH: "assistant.creativity.high",
};

export const REASONING_LABELS: Record<Reasoning, string> = {
    OFF: "assistant.reasoning.off",
    LOW: "assistant.reasoning.low",
    MEDIUM: "assistant.reasoning.medium",
    HIGH: "assistant.reasoning.high",
};

export const ANSWER_SOURCES: AnswerSource[] = ["DOCUMENTS_ONLY", "DOCUMENTS_FIRST"];
export const CREATIVITIES: Creativity[] = ["LOW", "MEDIUM", "HIGH"];
export const REASONINGS: Reasoning[] = ["OFF", "LOW", "MEDIUM", "HIGH"];

/** Like the backend. */
export const MAX_INSTRUCTIONS = 4000;
export const MAX_TEMPLATE_NAME = 80;
export const MIN_SEARCH_RESULTS = 3;
export const MAX_SEARCH_RESULTS = 20;

/** How a tenant's assistant answers: its instructions (sent with every question) and its defaults. */
export interface AssistantSettings {
    instructions: string;
    answerSource: AnswerSource;
    creativity: Creativity;
    reasoning: Reasoning;
    searchResults: number;
    /** The chat model of questions that choose none - those of Claude Code (MCP); null: none. */
    defaultModelId: number | null;
    /** The image model the chat creates images with; null: the only one the tenant may use, else none. */
    imageModelId: number | null;
}

/** A model the tenant may use - to choose as its default (chat) or image model. */
export interface ModelOption {
    id: number;
    displayName: string;
}

/** A template the users choose in the chat; a setting null takes the tenant's. */
export interface ChatTemplate {
    id: number;
    name: string;
    instructions: string;
    answerSource: AnswerSource | null;
    creativity: Creativity | null;
    reasoning: Reasoning | null;
    searchResults: number | null;
    createdAt: string;
    updatedAt: string;
}

export interface ChatTemplateRequest {
    name: string;
    instructions: string;
    answerSource: AnswerSource | null;
    creativity: Creativity | null;
    reasoning: Reasoning | null;
    searchResults: number | null;
}

/** A template as the chat offers it - its name only. */
export interface ChatTemplateOption {
    id: number;
    name: string;
}
