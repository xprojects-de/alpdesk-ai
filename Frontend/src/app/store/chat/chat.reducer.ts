import {ChatTemplateOption} from "../../models/assistant.model";
import {createReducer, on} from "@ngrx/store";
import {SoftwarePrompt} from "../../models/mcp.model";
import {
    ChoiceId,
    ActionDecision,
    ChatFile,
    ChatMessage,
    Conversation,
    PendingActionStatus,
    SoftwareChoice,
    ToolStep,
} from "../../models/chat.model";
import * as ChatActions from "./chat.actions";
import {storedChoice, toggled} from "../../utils/software-choice.util";

export interface PendingExchange {
    /** The conversation asked in; null: a new one. */
    conversationId: number | null;
    /** null: no new question - the model goes on after a decided tool call. */
    question: string | null;
    answer: string;
    sources: string[];
    /** The model's reasoning so far, where its provider hands it over. */
    thinking: string;
    /** The tools called so far, in their order. */
    tools: ToolStep[];
    /** The last tool is still at work - nothing of the model came after it yet. */
    toolRunning: boolean;
}

export interface ChatState {
    /** Newest first. */
    conversations: Conversation[];
    /** null: a new chat, not started yet. */
    selectedId: number | null;
    messages: ChatMessage[];
    messagesLoading: boolean;
    /** The question being answered, shown right away in its conversation with the answer streamed so far. */
    pending: PendingExchange | null;
    /** The sources of the answers given in this session, by answer id - stored messages have none. */
    sources: Record<number, string[]>;
    /** The reasoning behind the answers given in this session, by answer id - it is never stored. */
    thinking: Record<number, string>;
    /** The tools the answers given in this session called, by answer id - they are never stored. */
    tools: Record<number, ToolStep[]>;
    /** The open conversation's files, uploaded and generated, oldest first. */
    files: ChatFile[];
    /** Files are being uploaded. */
    uploading: boolean;
    /** The templates of the tenant's assistant the user may choose. */
    templates: ChatTemplateOption[];
    /** The prompts of the connected software the user may use. */
    softwarePrompts: SoftwarePrompt[];
    /** The connected software the user reaches (the topics). */
    software: SoftwareChoice[];
    /** The topics the open conversation's questions leave out - from the conversation, none for a new one. */
    excludedConnectionIds: ChoiceId[];
    /**
     * The open conversation's created images as object URLs, by file id; null while loading or after a failure (no
     * retry). Dropped with the conversation - ChatEffects releases the URLs then.
     */
    imagePreviews: Record<number, string | null>;
}

export const initialState: ChatState = {
    conversations: [],
    selectedId: null,
    messages: [],
    messagesLoading: false,
    pending: null,
    sources: {},
    thinking: {},
    tools: {},
    files: [],
    uploading: false,
    templates: [],
    softwarePrompts: [],
    software: [],
    excludedConnectionIds: [],
    imagePreviews: {},
};

/** The waiting calls of the messages with this status; only waiting ones change. */
function withActions(
    messages: ChatMessage[],
    status: PendingActionStatus,
    which: (actionId: number) => boolean,
): ChatMessage[] {
    return messages.map(message =>
        message.action?.status === "PENDING" && which(message.action.id)
            ? {...message, action: {...message.action, status}}
            : message,
    );
}

const DECIDED: Record<ActionDecision, PendingActionStatus> = {run: "RUN", cancel: "CANCELLED"};

/** Keeps an answer's reasoning for this session - nothing for an answer without. */
function withThinking(
    thinking: Record<number, string>,
    answerId: number,
    text: string | undefined,
): Record<number, string> {
    return text ? {...thinking, [answerId]: text} : thinking;
}

/** Keeps the tools an answer called for this session - nothing for an answer without. */
function withTools(tools: Record<number, ToolStep[]>, answerId: number, steps: ToolStep[] | undefined) {
    return steps?.length ? {...tools, [answerId]: steps} : tools;
}

/** A pending exchange with nothing streamed yet. */
function pendingExchange(conversationId: number | null, question: string | null): PendingExchange {
    return {conversationId, question, answer: "", sources: [], thinking: "", tools: [], toolRunning: false};
}

/** Puts the conversation first (the list is newest first), replacing its old entry. */
function toTop(conversations: Conversation[], conversation: Conversation): Conversation[] {
    return [conversation, ...conversations.filter(c => c.id !== conversation.id)];
}

/** A conversation's stored choice of software or skills, as the chips' ids. */
function storedOf(conversation: Conversation | undefined): ChoiceId[] {
    return conversation ? storedChoice(conversation.excludedConnectionIds, conversation.excludedSkillIds) : [];
}

export const chatReducer = createReducer(
    initialState,

    on(ChatActions.loadConversationsSuccess, (state, {conversations}) => ({...state, conversations})),
    on(ChatActions.loadTemplatesSuccess, (state, {templates}) => ({...state, templates})),
    on(ChatActions.loadSoftwarePromptsSuccess, (state, {prompts}) => ({...state, softwarePrompts: prompts})),
    on(ChatActions.loadSoftwareSuccess, (state, {software}) => ({...state, software})),
    on(ChatActions.toggleSoftware, (state, {connectionId}) => ({
        ...state,
        excludedConnectionIds: toggled(state.excludedConnectionIds, connectionId),
    })),

    on(ChatActions.selectConversation, (state, {id}) => ({
        ...state,
        selectedId: id,
        messages: state.selectedId === id ? state.messages : [],
        files: state.selectedId === id ? state.files : [],
        imagePreviews: state.selectedId === id ? state.imagePreviews : {},
        excludedConnectionIds:
            state.selectedId === id
                ? state.excludedConnectionIds
                : storedOf(state.conversations.find(c => c.id === id)),
        messagesLoading: true,
    })),
    on(ChatActions.loadMessagesSuccess, (state, {conversationId, messages}) =>
        state.selectedId === conversationId ? {...state, messages, messagesLoading: false} : state,
    ),
    on(ChatActions.loadMessagesFailure, state => ({...state, messagesLoading: false})),
    on(ChatActions.newConversation, state => ({
        ...state,
        selectedId: null,
        messages: [],
        files: [],
        imagePreviews: {},
        excludedConnectionIds: [],
        messagesLoading: false,
    })),

    on(ChatActions.sendMessage, (state, {request}) => ({
        ...state,
        pending: pendingExchange(request.conversationId, request.message),
    })),
    // The pieces keep coming in while another conversation is open - they are only not shown there.
    on(ChatActions.streamSources, (state, {sources}) =>
        state.pending ? {...state, pending: {...state.pending, sources}} : state,
    ),
    on(ChatActions.streamDelta, (state, {text}) =>
        state.pending
            ? {...state, pending: {...state.pending, answer: state.pending.answer + text, toolRunning: false}}
            : state,
    ),
    on(ChatActions.streamThinking, (state, {text}) =>
        state.pending
            ? {...state, pending: {...state.pending, thinking: state.pending.thinking + text, toolRunning: false}}
            : state,
    ),
    on(ChatActions.streamTool, (state, {step}) =>
        state.pending
            ? {...state, pending: {...state.pending, tools: [...state.pending.tools, step], toolRunning: true}}
            : state,
    ),
    on(ChatActions.sendMessageSuccess, (state, {conversationId, response}) => {
        const {conversation, question, answer, sources} = response;
        const conversations = toTop(state.conversations, conversation);
        const shown = state.selectedId === conversationId;
        return {
            ...state,
            conversations,
            // A new chat's first answer opens the conversation it started.
            selectedId: shown ? conversation.id : state.selectedId,
            // A new question expires the calls still waiting in its conversation (the backend did the same).
            messages: shown
                ? [...withActions(state.messages, "EXPIRED", () => true), ...(question ? [question] : []), answer]
                : state.messages,
            // The topics stay as the chips show them - one switched while the answer came goes with the next question.
            pending: null,
            sources: {...state.sources, [answer.id]: sources},
            thinking: withThinking(state.thinking, answer.id, state.pending?.thinking),
            tools: withTools(state.tools, answer.id, state.pending?.tools),
        };
    }),
    on(ChatActions.stoppedAnswerStored, (state, {response}) => ({
        ...state,
        conversations: toTop(state.conversations, response.conversation),
    })),
    on(ChatActions.sendMessageFailure, ChatActions.stopStreaming, ChatActions.decideActionFailure, state => ({
        ...state,
        pending: null,
    })),

    on(ChatActions.decideAction, (state, {conversationId}) => ({
        ...state,
        pending: pendingExchange(conversationId, null),
    })),
    on(ChatActions.decideActionSuccess, (state, {conversationId, actionId, decision, response}) => {
        const shown = state.selectedId === conversationId;
        const decided = withActions(state.messages, DECIDED[decision], id => id === actionId);
        return {
            ...state,
            conversations: toTop(state.conversations, response.conversation),
            messages: shown ? [...decided, response.answer] : state.messages,
            pending: null,
            sources: {...state.sources, [response.answer.id]: response.sources},
            thinking: withThinking(state.thinking, response.answer.id, state.pending?.thinking),
            tools: withTools(state.tools, response.answer.id, state.pending?.tools),
        };
    }),

    on(ChatActions.deleteConversationSuccess, (state, {id}) => {
        const conversations = state.conversations.filter(c => c.id !== id);
        return state.selectedId === id
            ? {...state, conversations, selectedId: null, messages: [], files: [], imagePreviews: {}}
            : {...state, conversations};
    }),

    on(ChatActions.loadFilesSuccess, (state, {conversationId, files}) =>
        state.selectedId === conversationId ? {...state, files} : state,
    ),
    on(ChatActions.uploadFiles, state => ({...state, uploading: true})),
    on(ChatActions.uploadFilesSuccess, (state, {conversationId, response}) => {
        const shown = state.selectedId === conversationId;
        return {
            ...state,
            conversations: toTop(state.conversations, response.conversation),
            // Files uploaded without a conversation open the one they started.
            selectedId: shown ? response.conversation.id : state.selectedId,
            files: shown ? [...state.files, ...response.files] : state.files,
            uploading: false,
        };
    }),
    on(ChatActions.uploadFilesFailure, state => ({...state, uploading: false})),
    on(ChatActions.deleteFileSuccess, (state, {id}) => ({...state, files: state.files.filter(f => f.id !== id)})),

    on(ChatActions.loadImagePreview, (state, {file}) => ({
        ...state,
        imagePreviews: {...state.imagePreviews, [file.id]: null},
    })),
    on(ChatActions.loadImagePreviewSuccess, (state, {fileId, url}) => ({
        ...state,
        imagePreviews: {...state.imagePreviews, [fileId]: url},
    })),
);
