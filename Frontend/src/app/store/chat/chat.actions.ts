import {ChatTemplateOption} from "../../models/assistant.model";
import {createAction, props} from "@ngrx/store";
import {SoftwarePrompt} from "../../models/mcp.model";
import {
    ChoiceId,
    ActionDecision,
    ChatFile,
    ChatFileUploadResponse,
    ChatMessage,
    ChatRequest,
    ChatResponse,
    Conversation,
    SoftwareChoice,
    ToolStep,
} from "../../models/chat.model";

export const loadConversations = createAction("[Chat] Load Conversations");
export const loadConversationsSuccess = createAction(
    "[Chat] Load Conversations Success",
    props<{conversations: Conversation[]}>(),
);
export const loadConversationsFailure = createAction("[Chat] Load Conversations Failure", props<{error: string}>());

/** Opens a conversation and loads its messages. */
export const loadTemplates = createAction("[Chat] Load Templates");
export const loadTemplatesSuccess = createAction(
    "[Chat] Load Templates Success",
    props<{templates: ChatTemplateOption[]}>(),
);
export const loadTemplatesFailure = createAction("[Chat] Load Templates Failure", props<{error: string}>());

/** The prompts of the connected software the user may use - chosen in the chat, filled in a dialog. */
export const loadSoftwarePrompts = createAction("[Chat] Load Software Prompts");
export const loadSoftwarePromptsSuccess = createAction(
    "[Chat] Load Software Prompts Success",
    props<{prompts: SoftwarePrompt[]}>(),
);
export const loadSoftwarePromptsFailure = createAction(
    "[Chat] Load Software Prompts Failure",
    props<{error: string}>(),
);

/** The connected software the user reaches - a chip each above the input, a question may leave some out. */
export const loadSoftware = createAction("[Chat] Load Software");
export const loadSoftwareSuccess = createAction("[Chat] Load Software Success", props<{software: SoftwareChoice[]}>());
export const loadSoftwareFailure = createAction("[Chat] Load Software Failure", props<{error: string}>());
/** Switches one software on or off for the open conversation - sent with its next question. */
export const toggleSoftware = createAction("[Chat] Toggle Software", props<{connectionId: ChoiceId}>());

/** The software's text of a prompt with the user's values: it goes into the input field, not sent. */
export const fillSoftwarePrompt = createAction(
    "[Chat] Fill Software Prompt",
    props<{promptId: number; values: Record<string, string>}>(),
);
export const fillSoftwarePromptSuccess = createAction("[Chat] Fill Software Prompt Success", props<{text: string}>());
export const fillSoftwarePromptFailure = createAction("[Chat] Fill Software Prompt Failure", props<{error: string}>());

export const selectConversation = createAction("[Chat] Select Conversation", props<{id: number}>());
export const loadMessagesSuccess = createAction(
    "[Chat] Load Messages Success",
    props<{conversationId: number; messages: ChatMessage[]}>(),
);
export const loadMessagesFailure = createAction("[Chat] Load Messages Failure", props<{error: string}>());

/** An empty chat; the first question starts the conversation. */
export const newConversation = createAction("[Chat] New Conversation");

/** Starts the streamed answer; streamSources, then streamDelta per piece, last sendMessageSuccess or -Failure. */
export const sendMessage = createAction("[Chat] Send Message", props<{request: ChatRequest}>());
export const streamSources = createAction("[Chat] Stream Sources", props<{sources: string[]}>());
export const streamDelta = createAction("[Chat] Stream Delta", props<{text: string}>());
/** A piece of the model's reasoning while it thinks - shown in this session, never stored. */
export const streamThinking = createAction("[Chat] Stream Thinking", props<{text: string}>());
/** A tool about to run - shown in this session, never stored. */
export const streamTool = createAction("[Chat] Stream Tool", props<{step: ToolStep}>());
/** Stops showing the answer; the backend still finishes and stores it. */
export const stopStreaming = createAction("[Chat] Stop Streaming");
/**
 * A stopped answer the backend stored after all. askedIn: the conversation it was asked in, null for a new chat - its
 * conversation exists only now.
 */
export const stoppedAnswerStored = createAction(
    "[Chat] Stopped Answer Stored",
    props<{askedIn: number | null; response: ChatResponse}>(),
);
/** A stopped answer that failed after all ("error") - nothing of it was stored. */
export const stoppedAnswerFailed = createAction(
    "[Chat] Stopped Answer Failed",
    props<{askedIn: number | null; error: string}>(),
);
/**
 * The stored exchange ("done"). Carries the request's conversationId (null for a new one), so a late answer
 * lands in the right chat.
 */
export const sendMessageSuccess = createAction(
    "[Chat] Send Message Success",
    props<{conversationId: number | null; response: ChatResponse}>(),
);
export const sendMessageFailure = createAction("[Chat] Send Message Failure", props<{error: string}>());

/**
 * Runs or cancels a changing tool call the chat showed; the model goes on in the same stream as an answer
 * (streamSources, streamDelta), last decideActionSuccess or -Failure.
 */
export const decideAction = createAction(
    "[Chat] Decide Action",
    props<{conversationId: number; actionId: number; decision: ActionDecision}>(),
);
export const decideActionSuccess = createAction(
    "[Chat] Decide Action Success",
    props<{conversationId: number; actionId: number; decision: ActionDecision; response: ChatResponse}>(),
);
/** Refused (decided or expired meanwhile, the tool taken back) or failed - the conversation is read again. */
export const decideActionFailure = createAction(
    "[Chat] Decide Action Failure",
    props<{conversationId: number; error: string}>(),
);

export const deleteConversation = createAction("[Chat] Delete Conversation", props<{id: number}>());
export const deleteConversationSuccess = createAction("[Chat] Delete Conversation Success", props<{id: number}>());
export const deleteConversationFailure = createAction("[Chat] Delete Conversation Failure", props<{error: string}>());

/** The open conversation's files - loaded with it, and again after an answer that created files. */
export const loadFiles = createAction("[Chat] Load Files", props<{conversationId: number}>());
export const loadFilesSuccess = createAction(
    "[Chat] Load Files Success",
    props<{conversationId: number; files: ChatFile[]}>(),
);
export const loadFilesFailure = createAction("[Chat] Load Files Failure", props<{error: string}>());

/** Without a conversationId the backend starts a new conversation for the files. */
export const uploadFiles = createAction("[Chat] Upload Files", props<{conversationId: number | null; files: File[]}>());
/** Carries the request's conversationId like sendMessageSuccess, so a late result lands in the right chat. */
export const uploadFilesSuccess = createAction(
    "[Chat] Upload Files Success",
    props<{conversationId: number | null; response: ChatFileUploadResponse}>(),
);
export const uploadFilesFailure = createAction("[Chat] Upload Files Failure", props<{error: string}>());

export const deleteFile = createAction("[Chat] Delete File", props<{id: number}>());
export const deleteFileSuccess = createAction("[Chat] Delete File Success", props<{id: number}>());
export const deleteFileFailure = createAction("[Chat] Delete File Failure", props<{error: string}>());

/** Loads the file and saves it as a download - the content never enters the store. */
export const downloadFile = createAction("[Chat] Download File", props<{file: ChatFile}>());
export const downloadFileFailure = createAction("[Chat] Download File Failure", props<{error: string}>());

/**
 * Loads an image the model created for the answer to show it: as an object URL - the content stays out of the store.
 * A failure is silent: the image's chip still downloads it, and says why when that fails too.
 */
export const loadImagePreview = createAction("[Chat] Load Image Preview", props<{file: ChatFile}>());
export const loadImagePreviewSuccess = createAction(
    "[Chat] Load Image Preview Success",
    props<{fileId: number; url: string}>(),
);
export const imagePreviewFailure = createAction("[Chat] Image Preview Failure", props<{fileId: number}>());
