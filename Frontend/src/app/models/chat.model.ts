export interface Conversation {
    id: number;
    title: string;
    /** The template of its last question; null for the tenant's settings alone. */
    templateId: number | null;
    createdAt: string;
    updatedAt: string;
    /** The connected software (topics) its questions leave out - the last choice sent with a question. */
    excludedConnectionIds: number[];
    /** The A2A agent's skills its questions leave out - for a tenant answering through its agent. */
    excludedSkillIds: string[];
}

/** A connected software the user reaches - a topic a question may leave out (the chips above the input). */
/** What a chip stands for: a connection (its id) or a skill of the agent ("skill:<id>"). */
export type ChoiceId = number | string;

export interface SoftwareChoice {
    /** A connection's id - or, for a tenant answering through its A2A agent, "skill:" and a skill's id. */
    id: ChoiceId;
    name: string;
    /** What the software's server says about itself - the chip's tooltip; null: nothing. */
    instructions: string | null;
}

export interface ChatMessage {
    id: number;
    role: "USER" | "ASSISTANT";
    content: string;
    /** The model that wrote an answer; null for a question. */
    modelName: string | null;
    /** The files an answer created; empty otherwise. */
    files: ChatFile[];
    createdAt: string;
    /** The changing tool call of the company's software the answer stopped at; null for none. */
    action: PendingAction | null;
    /** The answer is the A2A agent's question back - the next question answers it. */
    waiting: boolean;
}

export type PendingActionStatus = "PENDING" | "RUN" | "CANCELLED" | "EXPIRED";

/** A changing tool call of the company's connected software, waiting for the user to run or cancel it. */
export interface PendingAction {
    id: number;
    software: string;
    tool: string;
    arguments: Record<string, unknown>;
    status: PendingActionStatus;
    /** What the software answered when it was run. */
    result: string | null;
    createdAt: string;
}

/** What the user decides about a waiting call. */
export type ActionDecision = "run" | "cancel";

/** A file of a conversation: uploaded by the user or created by the model. Every question is sent with all of them. */
export interface ChatFile {
    id: number;
    origin: "UPLOADED" | "GENERATED";
    fileName: string;
    contentType: string;
    sizeBytes: number;
    /** Images the file counts as (an image, the pages of a scanned PDF) - limited per conversation. */
    imageCount: number;
    createdAt: string;
    /** An uploaded video deleted after the installation's retention time: listed, no longer downloadable. */
    expired: boolean;
}

export interface ChatFileUploadResponse {
    /** The conversation the files were added to - a new one if none was given. */
    conversation: Conversation;
    files: ChatFile[];
}

export interface ChatRequest {
    /** null starts a new conversation. */
    conversationId: number | null;
    /** null: the tenant's chat answers through its A2A agent - no model of ours. */
    modelId: number | null;
    message: string;
    /** A template of the tenant's assistant; null: its settings alone. */
    templateId: number | null;
    /** The connected software this question leaves out - kept with the conversation (empty: every one on). */
    excludedConnectionIds?: number[];
    /** The agent's skills this question leaves out (their ids, without "skill:"). */
    excludedSkillIds?: string[];
}

export interface ChatResponse {
    conversation: Conversation;
    /** null for an answer that went on after a decided tool call. */
    question: ChatMessage | null;
    answer: ChatMessage;
    /** The file names of the documents the answer drew on. */
    sources: string[];
}

/** A tool about to run while the answer comes - shown in this session, never stored. */
export interface ToolStep {
    /** The tool's name, e.g. similarity_search - for a connected software's tool its own name there. */
    name: string;
    /** The connected software the tool belongs to; null for the application's own tools. */
    software: string | null;
}

/** What POST /tenant/chat/stream sends: the sources first, then the answer in pieces, last done or error. */
export type ChatStreamEvent =
    | {type: "sources"; sources: string[]}
    | {type: "delta"; text: string}
    /** A piece of the model's reasoning, where its provider hands it over - shown while it thinks, never stored. */
    | {type: "thinking"; text: string}
    /** A tool about to run. */
    | {type: "tool"; step: ToolStep}
    /** The stored exchange - the last event. */
    | {type: "done"; response: ChatResponse}
    /** The provider failed, nothing was stored - the last event. Like an HTTP error body (docs/architecture/i18n.md). */
    | {type: "error"; message: string; code?: string; params?: Record<string, string>};
