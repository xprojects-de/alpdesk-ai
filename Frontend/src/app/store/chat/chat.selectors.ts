import {createFeatureSelector, createSelector} from "@ngrx/store";
import {ChatState} from "./chat.reducer";

export const selectChatState = createFeatureSelector<ChatState>("chat");

export const selectConversations = createSelector(selectChatState, state => state.conversations);

export const selectSelectedConversationId = createSelector(selectChatState, state => state.selectedId);

export const selectMessages = createSelector(selectChatState, state => state.messages);

export const selectMessagesLoading = createSelector(selectChatState, state => state.messagesLoading);

export const selectSources = createSelector(selectChatState, state => state.sources);

/** The reasoning behind this session's answers, by answer id. */
export const selectThinking = createSelector(selectChatState, state => state.thinking);
export const selectTools = createSelector(selectChatState, state => state.tools);

/** A question is waiting for its answer (in any conversation) - one at a time. */
export const selectSending = createSelector(selectChatState, state => state.pending !== null);

/** The question being answered and the answer so far, if they belong to the conversation shown. */
export const selectPendingExchange = createSelector(selectChatState, state =>
    state.pending && state.pending.conversationId === state.selectedId ? state.pending : null,
);

export const selectFiles = createSelector(selectChatState, state => state.files);

export const selectUploading = createSelector(selectChatState, state => state.uploading);

export const selectTemplates = createSelector(selectChatState, state => state.templates);
export const selectSoftwarePrompts = createSelector(selectChatState, state => state.softwarePrompts);

export const selectImagePreviews = createSelector(selectChatState, state => state.imagePreviews);

export const selectSoftware = createSelector(selectChatState, state => state.software);
export const selectExcludedConnectionIds = createSelector(selectChatState, state => state.excludedConnectionIds);
