import {ChatTemplateOption} from "../models/assistant.model";
import {TenantModel} from "../models/ai.model";
import {Conversation} from "../models/chat.model";

/** A chat model the tenant can use: active (the list holds only those) and with a key for its provider. */
export function usableChatModels(models: TenantModel[]): TenantModel[] {
    return models.filter(model => model.kind === "CHAT" && model.keySource !== null);
}

/** The model to preselect: the one chosen last if it is still usable, else the first usable one. */
export function pickChatModel(models: TenantModel[], lastChosenId: number | null): number | null {
    const usable = usableChatModels(models);
    return usable.find(model => model.id === lastChosenId)?.id ?? usable[0]?.id ?? null;
}

/**
 * Whether there is a question to send: text typed, or a template chosen - a template can be the task itself, and
 * then its name is the question (the backend takes it the same way when the text is empty).
 */
export function hasQuestion(text: string, templateId: number | null): boolean {
    return text.trim().length > 0 || templateId !== null;
}

/** The text to send: what was typed, or the chosen template's name when nothing was - so the history shows it. */
export function questionToSend(text: string, template: ChatTemplateOption | undefined): string {
    return text.trim() || template?.name || "";
}

/** Up to this many files the strip above the input shows them all at once; more fold behind their count. */
export const FILES_SHOWN_OPEN = 4;

/**
 * Whether the conversation's files above the input are shown one by one: as the user chose, else open for a few - on a
 * phone never by itself: 30 stills of a video analysis filled the whole screen and hid the answer.
 */
export function filesExpanded(count: number, phone: boolean, chosen: boolean | null): boolean {
    return chosen ?? (!phone && count <= FILES_SHOWN_OPEN);
}

/** The template a conversation used last, if the tenant still has it - else null: the tenant's settings ("Standard"). */
export function pickTemplate(templates: ChatTemplateOption[], templateId: number | null | undefined): number | null {
    return templateId != null && templates.some(template => template.id === templateId) ? templateId : null;
}

/**
 * A source of an answer as the chat shows it: a document by its name, a web page the web search found as a link by its
 * host (the full address in its tooltip). Only http(s) becomes a link - a document's name never does.
 */
export interface AnswerSource {
    label: string;
    href: string | null;
}

export function answerSource(source: string): AnswerSource {
    if (!/^https?:\/\//i.test(source)) {
        return {label: source, href: null};
    }
    try {
        return {label: new URL(source).host, href: source};
    } catch {
        return {label: source, href: null};
    }
}

/** The conversation list's width in pixels: by default, and the range it can be dragged within. */
export const LIST_WIDTH = {default: 260, min: 180, max: 600};

/** A dragged or stored width, kept within the range - a stored value that is no number gives the default. */
export function clampListWidth(width: number): number {
    if (!Number.isFinite(width)) {
        return LIST_WIDTH.default;
    }
    return Math.round(Math.min(LIST_WIDTH.max, Math.max(LIST_WIDTH.min, width)));
}

/** How close to the end a list counts as at its end - a pixel or two off after a resize must not stop following. */
const FOLLOW_SLACK_PX = 80;

/** Whether the reader is at the newest message: the chat follows the answer then, not once they scrolled up to read. */
export function atLatest(scrollTop: number, scrollHeight: number, clientHeight: number): boolean {
    return scrollHeight - scrollTop - clientHeight <= FOLLOW_SLACK_PX;
}

/** Conversations under a heading of when they were last used. */
export interface ConversationGroup {
    /** The heading's translation key. */
    label: string;
    conversations: Conversation[];
}

/**
 * The conversation list by when a conversation was last used - today, yesterday, the last seven days, older - each in
 * the order given (newest first), groups without a conversation left out. Days are local calendar days.
 */
export function groupConversations(conversations: Conversation[], now: Date): ConversationGroup[] {
    const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const day = 24 * 60 * 60 * 1000;
    const groups: ConversationGroup[] = [
        {label: "chat.groups.today", conversations: []},
        {label: "chat.groups.yesterday", conversations: []},
        {label: "chat.groups.lastSevenDays", conversations: []},
        {label: "chat.groups.older", conversations: []},
    ];
    for (const conversation of conversations) {
        const updated = new Date(conversation.updatedAt).getTime();
        const index =
            updated >= startOfToday ? 0 : updated >= startOfToday - day ? 1 : updated >= startOfToday - 6 * day ? 2 : 3;
        groups[index].conversations.push(conversation);
    }
    return groups.filter(group => group.conversations.length > 0);
}

/** The conversations whose title contains the search text (any case). */
export function filterConversations(conversations: Conversation[], query: string): Conversation[] {
    const text = query.trim().toLocaleLowerCase();
    return conversations.filter(conversation => conversation.title.toLocaleLowerCase().includes(text));
}

/**
 * The conversation to open again once a stopped answer is stored: the one shown, if it is the answer's - or the new
 * chat it started, while the user is still on the empty new chat and asks nothing else. Null: nothing to open.
 */
export function conversationToReopen(
    storedIn: number,
    askedIn: number | null,
    selectedId: number | null,
    sending: boolean,
): number | null {
    if (selectedId === storedIn) {
        return storedIn;
    }
    return askedIn === null && selectedId === null && !sending ? storedIn : null;
}
