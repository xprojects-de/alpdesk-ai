import {TenantModel} from "../models/ai.model";
import {Conversation} from "../models/chat.model";
import {
    answerSource,
    atLatest,
    clampListWidth,
    conversationToReopen,
    filesExpanded,
    filterConversations,
    groupConversations,
    hasQuestion,
    LIST_WIDTH,
    pickChatModel,
    pickTemplate,
    questionToSend,
    usableChatModels,
} from "./chat.util";

describe("atLatest", () => {
    it("is at the newest message at the end, or a little short of it - not once scrolled up", () => {
        expect(atLatest(900, 1500, 600)).toBe(true);
        expect(atLatest(850, 1500, 600)).toBe(true);
        expect(atLatest(500, 1500, 600)).toBe(false);
        expect(atLatest(0, 400, 600)).toBe(true);
    });
});

describe("chat.util", () => {
    const model = (id: number, changes: Partial<TenantModel> = {}): TenantModel => ({
        id,
        provider: "OPENAI",
        kind: "CHAT",
        displayName: `Modell ${id}`,
        dimension: null,
        keySource: "OWN",
        ...changes,
    });
    const models = [
        model(1, {keySource: null}),
        model(2, {kind: "EMBEDDING"}),
        model(3),
        model(4, {keySource: "GLOBAL"}),
    ];

    it("offers only chat models with a key", () => {
        expect(usableChatModels(models).map(m => m.id)).toEqual([3, 4]);
    });

    it("keeps the last chosen model while it is usable, else takes the first usable one", () => {
        expect(pickChatModel(models, 4)).toBe(4);
        expect(pickChatModel(models, 1)).toBe(3);
        expect(pickChatModel(models, null)).toBe(3);
        expect(pickChatModel([model(1, {keySource: null})], null)).toBeNull();
    });

    it("picks the conversation's template while the tenant still has it", () => {
        const templates = [
            {id: 1, name: "Planer"},
            {id: 2, name: "Nachschlagen"},
        ];
        expect(pickTemplate(templates, 2)).toBe(2);
        expect(pickTemplate(templates, 9)).toBeNull();
        expect(pickTemplate(templates, null)).toBeNull();
        expect(pickTemplate([], 1)).toBeNull();
    });

    it("has a question to send with text typed or a template chosen", () => {
        expect(hasQuestion("Wie?", null)).toBe(true);
        expect(hasQuestion("  ", null)).toBe(false);
        expect(hasQuestion("", 2)).toBe(true);
    });

    it("shows a few files open, folds many and all on a phone - unless the user chose", () => {
        expect(filesExpanded(4, false, null)).toBe(true);
        expect(filesExpanded(5, false, null)).toBe(false);
        expect(filesExpanded(1, true, null)).toBe(false);
        expect(filesExpanded(30, true, true)).toBe(true);
        expect(filesExpanded(2, false, false)).toBe(false);
    });

    it("sends the typed text, or the template's name when nothing was typed", () => {
        const template = {id: 2, name: "Videoanalyse"};
        expect(questionToSend("  Wie?  ", template)).toBe("Wie?");
        expect(questionToSend("", template)).toBe("Videoanalyse");
        expect(questionToSend("", undefined)).toBe("");
    });

    it("keeps the conversation list's width within its range", () => {
        expect(clampListWidth(300.4)).toBe(300);
        expect(clampListWidth(50)).toBe(LIST_WIDTH.min);
        expect(clampListWidth(5000)).toBe(LIST_WIDTH.max);
        expect(clampListWidth(Number("kaputt"))).toBe(LIST_WIDTH.default);
    });
});

describe("groupConversations", () => {
    const conversation = (title: string, updatedAt: Date) =>
        ({
            id: 0,
            title,
            templateId: null,
            createdAt: "",
            updatedAt: updatedAt.toISOString(),
            excludedConnectionIds: [],
            excludedSkillIds: [],
        }) as Conversation;
    const now = new Date(2026, 9, 9, 10, 30);

    it("sorts them under today, yesterday, the last seven days and older - in their order, empty groups left out", () => {
        const groups = groupConversations(
            [
                conversation("eben", new Date(2026, 9, 9, 10, 0)),
                conversation("heute früh", new Date(2026, 9, 9, 0, 5)),
                conversation("gestern spät", new Date(2026, 9, 8, 23, 59)),
                conversation("vor sechs Tagen", new Date(2026, 9, 3, 8, 0)),
                conversation("vor sieben Tagen", new Date(2026, 9, 2, 23, 0)),
            ],
            now,
        );

        expect(groups.map(group => [group.label, group.conversations.map(c => c.title)])).toEqual([
            ["chat.groups.today", ["eben", "heute früh"]],
            ["chat.groups.yesterday", ["gestern spät"]],
            ["chat.groups.lastSevenDays", ["vor sechs Tagen"]],
            ["chat.groups.older", ["vor sieben Tagen"]],
        ]);
    });

    it("gives no group without conversations", () => {
        expect(groupConversations([], now)).toEqual([]);
    });

    it("finds conversations by their title, in any case", () => {
        const list = [conversation("Wartung Anlage B", now), conversation("Angebot kürzen", now)];

        expect(filterConversations(list, " anlage ").map(c => c.title)).toEqual(["Wartung Anlage B"]);
        expect(filterConversations(list, "")).toHaveLength(2);
    });
});

describe("conversationToReopen", () => {
    it("reloads the conversation shown when the stopped answer is stored there", () => {
        expect(conversationToReopen(7, 7, 7, false)).toBe(7);
        expect(conversationToReopen(7, 7, 7, true)).toBe(7);
    });

    it("opens a stopped new chat's conversation while the user is still on the empty new chat", () => {
        expect(conversationToReopen(7, null, null, false)).toBe(7);
    });

    it("leaves the user where they are: another conversation, a new question on its way, or a chat asked elsewhere", () => {
        expect(conversationToReopen(7, null, 3, false)).toBeNull();
        expect(conversationToReopen(7, null, null, true)).toBeNull();
        expect(conversationToReopen(7, 7, null, false)).toBeNull();
    });
});

describe("answerSource", () => {
    it("shows a web page as a link by its host, a document by its name", () => {
        expect(answerSource("https://www.example.com/seite?x=1")).toEqual({
            label: "www.example.com",
            href: "https://www.example.com/seite?x=1",
        });
        expect(answerSource("http://example.org/a")).toEqual({label: "example.org", href: "http://example.org/a"});
        expect(answerSource("Handbuch.pdf")).toEqual({label: "Handbuch.pdf", href: null});
        expect(answerSource("javascript:alert(1)")).toEqual({label: "javascript:alert(1)", href: null});
    });
});
