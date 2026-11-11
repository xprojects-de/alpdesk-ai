import {ChatFile, ChatMessage, ChatResponse, Conversation, PendingAction} from "../../models/chat.model";
import * as ChatActions from "./chat.actions";
import {chatReducer, initialState} from "./chat.reducer";

describe("chatReducer", () => {
    const conversation = (id: number): Conversation => ({
        id,
        title: `Chat ${id}`,
        templateId: null,
        createdAt: "",
        updatedAt: "",
        excludedConnectionIds: [],
        excludedSkillIds: [],
    });
    const message = (id: number, role: ChatMessage["role"]): ChatMessage => ({
        id,
        role,
        content: `Nachricht ${id}`,
        modelName: role === "ASSISTANT" ? "gpt-5" : null,
        files: [],
        createdAt: "",
        action: null,
        waiting: false,
    });
    const response = (conversationId: number): ChatResponse => ({
        conversation: conversation(conversationId),
        question: message(10, "USER"),
        answer: message(11, "ASSISTANT"),
        sources: ["a.pdf"],
    });
    const send = (conversationId: number | null) =>
        ChatActions.sendMessage({
            request: {conversationId, modelId: 1, message: "Wann ist Inventur?", templateId: null},
        });

    it("shows the question at once and opens the conversation a new chat started", () => {
        let state = chatReducer(initialState, ChatActions.loadConversationsSuccess({conversations: [conversation(1)]}));
        state = chatReducer(state, send(null));
        state = chatReducer(state, ChatActions.streamSources({sources: ["a.pdf"]}));
        state = chatReducer(state, ChatActions.streamDelta({text: "Ab "}));
        state = chatReducer(state, ChatActions.streamDelta({text: "15 Uhr."}));
        expect(state.pending).toEqual({
            conversationId: null,
            question: "Wann ist Inventur?",
            answer: "Ab 15 Uhr.",
            sources: ["a.pdf"],
            thinking: "",
            tools: [],
            toolRunning: false,
        });

        state = chatReducer(state, ChatActions.sendMessageSuccess({conversationId: null, response: response(2)}));

        expect(state.selectedId).toBe(2);
        expect(state.conversations.map(c => c.id)).toEqual([2, 1]);
        expect(state.messages.map(m => m.id)).toEqual([10, 11]);
        expect(state.sources[11]).toEqual(["a.pdf"]);
        expect(state.pending).toBeNull();
    });

    it("does not put a late answer into the conversation opened meanwhile", () => {
        let state = chatReducer(initialState, ChatActions.selectConversation({id: 1}));
        state = chatReducer(state, send(1));
        state = chatReducer(state, ChatActions.selectConversation({id: 3}));
        state = chatReducer(
            state,
            ChatActions.loadMessagesSuccess({conversationId: 3, messages: [message(5, "USER")]}),
        );

        state = chatReducer(state, ChatActions.sendMessageSuccess({conversationId: 1, response: response(1)}));

        expect(state.selectedId).toBe(3);
        expect(state.messages.map(m => m.id)).toEqual([5]);
        expect(state.conversations.map(c => c.id)).toEqual([1]);
    });

    it("goes back to a new chat when the open conversation is deleted", () => {
        let state = chatReducer(initialState, ChatActions.loadConversationsSuccess({conversations: [conversation(1)]}));
        state = chatReducer(state, ChatActions.selectConversation({id: 1}));

        state = chatReducer(state, ChatActions.deleteConversationSuccess({id: 1}));

        expect(state.selectedId).toBeNull();
        expect(state.conversations).toEqual([]);
    });

    it("puts a stopped answer's conversation first once the backend stored it", () => {
        let state = chatReducer(initialState, ChatActions.loadConversationsSuccess({conversations: [conversation(1)]}));
        state = chatReducer(state, ChatActions.stoppedAnswerStored({askedIn: null, response: response(2)}));
        expect(state.conversations.map(c => c.id)).toEqual([2, 1]);
        expect(state.selectedId).toBeNull();
    });

    it("drops the pending exchange on an error event or a stop", () => {
        const streaming = chatReducer(chatReducer(initialState, send(null)), ChatActions.streamDelta({text: "Ab"}));

        expect(chatReducer(streaming, ChatActions.sendMessageFailure({error: "Provider failed"})).pending).toBeNull();
        expect(chatReducer(streaming, ChatActions.stopStreaming()).pending).toBeNull();
    });

    describe("files", () => {
        const file = (id: number): ChatFile => ({
            id,
            origin: "UPLOADED",
            fileName: `${id}.pdf`,
            contentType: "application/pdf",
            sizeBytes: 100,
            imageCount: 0,
            createdAt: "",
            expired: false,
        });

        it("opens the conversation an upload without one started", () => {
            let state = chatReducer(initialState, ChatActions.uploadFiles({conversationId: null, files: []}));
            expect(state.uploading).toBe(true);

            state = chatReducer(
                state,
                ChatActions.uploadFilesSuccess({
                    conversationId: null,
                    response: {conversation: conversation(4), files: [file(1), file(2)]},
                }),
            );

            expect(state.selectedId).toBe(4);
            expect(state.conversations.map(c => c.id)).toEqual([4]);
            expect(state.files.map(f => f.id)).toEqual([1, 2]);
            expect(state.uploading).toBe(false);
        });

        it("drops the files when another conversation is opened and ignores its late results", () => {
            let state = chatReducer(initialState, ChatActions.selectConversation({id: 1}));
            state = chatReducer(state, ChatActions.loadFilesSuccess({conversationId: 1, files: [file(1)]}));
            state = chatReducer(state, ChatActions.selectConversation({id: 2}));
            expect(state.files).toEqual([]);

            state = chatReducer(state, ChatActions.loadFilesSuccess({conversationId: 1, files: [file(1)]}));
            state = chatReducer(
                state,
                ChatActions.uploadFilesSuccess({
                    conversationId: 1,
                    response: {conversation: conversation(1), files: [file(3)]},
                }),
            );

            expect(state.selectedId).toBe(2);
            expect(state.files).toEqual([]);
        });

        it("removes a deleted file", () => {
            let state = chatReducer(initialState, ChatActions.selectConversation({id: 1}));
            state = chatReducer(state, ChatActions.loadFilesSuccess({conversationId: 1, files: [file(1), file(2)]}));

            state = chatReducer(state, ChatActions.deleteFileSuccess({id: 1}));

            expect(state.files.map(f => f.id)).toEqual([2]);
        });
    });

    describe("previews of created images", () => {
        const image = (id: number): ChatFile => ({
            id,
            origin: "GENERATED",
            fileName: `bild-${id}.png`,
            contentType: "image/png",
            sizeBytes: 100,
            imageCount: 1,
            createdAt: "",
            expired: false,
        });

        it("marks an image as loading, then keeps its object URL", () => {
            let state = chatReducer(initialState, ChatActions.selectConversation({id: 1}));
            state = chatReducer(state, ChatActions.loadImagePreview({file: image(5)}));
            expect(state.imagePreviews).toEqual({5: null});

            state = chatReducer(state, ChatActions.loadImagePreviewSuccess({fileId: 5, url: "blob:fuenf"}));
            expect(state.imagePreviews).toEqual({5: "blob:fuenf"});

            state = chatReducer(state, ChatActions.imagePreviewFailure({fileId: 6}));
            expect(state.imagePreviews).toEqual({5: "blob:fuenf"});
        });

        it("keeps them for the same conversation and drops them with another one, a new chat or the deleted one", () => {
            let state = chatReducer(initialState, ChatActions.selectConversation({id: 1}));
            state = chatReducer(state, ChatActions.loadImagePreviewSuccess({fileId: 5, url: "blob:fuenf"}));

            expect(chatReducer(state, ChatActions.selectConversation({id: 1})).imagePreviews).toEqual({
                5: "blob:fuenf",
            });
            expect(chatReducer(state, ChatActions.selectConversation({id: 2})).imagePreviews).toEqual({});
            expect(chatReducer(state, ChatActions.newConversation()).imagePreviews).toEqual({});
            expect(chatReducer(state, ChatActions.deleteConversationSuccess({id: 1})).imagePreviews).toEqual({});
        });
    });

    describe("a changing tool call an answer stopped at", () => {
        const action: PendingAction = {
            id: 5,
            software: "Buchhaltung",
            tool: "cancel_invoice",
            arguments: {number: "4711"},
            status: "PENDING",
            result: null,
            createdAt: "",
        };
        const opened = () => {
            const state = chatReducer(initialState, ChatActions.selectConversation({id: 2}));
            return chatReducer(
                state,
                ChatActions.loadMessagesSuccess({
                    conversationId: 2,
                    messages: [message(1, "USER"), {...message(2, "ASSISTANT"), action}],
                }),
            );
        };

        it("deciding streams the model's going on without a question; done marks the call and adds the answer", () => {
            let state = chatReducer(
                opened(),
                ChatActions.decideAction({conversationId: 2, actionId: 5, decision: "run"}),
            );
            expect(state.pending).toEqual({
                conversationId: 2,
                question: null,
                answer: "",
                sources: [],
                thinking: "",
                tools: [],
                toolRunning: false,
            });

            state = chatReducer(
                state,
                ChatActions.decideActionSuccess({
                    conversationId: 2,
                    actionId: 5,
                    decision: "run",
                    response: {...response(2), question: null},
                }),
            );
            expect(state.pending).toBeNull();
            expect(state.messages.map(m => m.id)).toEqual([1, 2, 11]);
            expect(state.messages[1].action?.status).toBe("RUN");
        });

        it("cancelled, the call shows so; refused, nothing changes but the pending answer", () => {
            const cancelled = chatReducer(
                opened(),
                ChatActions.decideActionSuccess({
                    conversationId: 2,
                    actionId: 5,
                    decision: "cancel",
                    response: {...response(2), question: null},
                }),
            );
            expect(cancelled.messages[1].action?.status).toBe("CANCELLED");

            let refused = chatReducer(
                opened(),
                ChatActions.decideAction({conversationId: 2, actionId: 5, decision: "run"}),
            );
            refused = chatReducer(refused, ChatActions.decideActionFailure({conversationId: 2, error: "expired"}));
            expect(refused.pending).toBeNull();
            expect(refused.messages[1].action?.status).toBe("PENDING");
        });

        it("a new question expires the calls still waiting", () => {
            const state = chatReducer(
                opened(),
                ChatActions.sendMessageSuccess({conversationId: 2, response: response(2)}),
            );
            expect(state.messages[1].action?.status).toBe("EXPIRED");
            expect(state.messages.map(m => m.id)).toEqual([1, 2, 10, 11]);
        });
    });

    it("collects the model's reasoning while it thinks and keeps it with the answer for this session", () => {
        let state = chatReducer(initialState, ChatActions.selectConversation({id: 2}));
        state = chatReducer(state, send(2));
        state = chatReducer(state, ChatActions.streamThinking({text: "Erst "}));
        state = chatReducer(state, ChatActions.streamThinking({text: "prüfen."}));
        expect(state.pending?.thinking).toBe("Erst prüfen.");

        state = chatReducer(state, ChatActions.sendMessageSuccess({conversationId: 2, response: response(2)}));
        expect(state.thinking[11]).toBe("Erst prüfen.");

        const without = chatReducer(
            chatReducer(state, send(2)),
            ChatActions.sendMessageSuccess({
                conversationId: 2,
                response: {...response(2), answer: {...response(2).answer, id: 12}},
            }),
        );
        expect(without.thinking[12]).toBeUndefined();
    });

    it("shows each tool while it runs and keeps the tools with the answer for this session", () => {
        const search = {name: "similarity_search", software: null};
        const read = {name: "read_document", software: null};
        let state = chatReducer(initialState, ChatActions.selectConversation({id: 2}));
        state = chatReducer(state, send(2));
        state = chatReducer(state, ChatActions.streamTool({step: search}));
        expect(state.pending?.toolRunning).toBe(true);

        state = chatReducer(state, ChatActions.streamThinking({text: "Gefunden."}));
        expect(state.pending?.toolRunning).toBe(false);

        state = chatReducer(state, ChatActions.streamTool({step: read}));
        state = chatReducer(state, ChatActions.streamDelta({text: "Ab 15 Uhr."}));
        expect(state.pending?.tools).toEqual([search, read]);
        expect(state.pending?.toolRunning).toBe(false);

        state = chatReducer(state, ChatActions.sendMessageSuccess({conversationId: 2, response: response(2)}));
        expect(state.tools[11]).toEqual([search, read]);

        const without = chatReducer(
            chatReducer(state, send(2)),
            ChatActions.sendMessageSuccess({
                conversationId: 2,
                response: {...response(2), answer: {...response(2).answer, id: 12}},
            }),
        );
        expect(without.tools[12]).toBeUndefined();
    });

    describe("the topics a conversation leaves out", () => {
        it("opens a conversation with its choice, a new chat with every topic on, a click switches one", () => {
            let state = chatReducer(
                initialState,
                ChatActions.loadConversationsSuccess({
                    conversations: [{...conversation(1), excludedConnectionIds: [7]}, conversation(2)],
                }),
            );

            state = chatReducer(state, ChatActions.selectConversation({id: 1}));
            expect(state.excludedConnectionIds).toEqual([7]);

            state = chatReducer(state, ChatActions.toggleSoftware({connectionId: 8}));
            expect(state.excludedConnectionIds).toEqual([7, 8]);

            state = chatReducer(state, ChatActions.selectConversation({id: 2}));
            expect(state.excludedConnectionIds).toEqual([]);

            state = chatReducer(state, ChatActions.toggleSoftware({connectionId: 7}));
            state = chatReducer(state, ChatActions.newConversation());
            expect(state.excludedConnectionIds).toEqual([]);
        });

        it("keeps a topic switched while the answer came - the next question sends what the chips show", () => {
            let state = chatReducer(initialState, ChatActions.selectConversation({id: 2}));
            state = chatReducer(state, send(2));
            state = chatReducer(state, ChatActions.toggleSoftware({connectionId: 9}));

            state = chatReducer(state, ChatActions.sendMessageSuccess({conversationId: 2, response: response(2)}));

            expect(state.excludedConnectionIds).toEqual([9]);
        });
    });
});
