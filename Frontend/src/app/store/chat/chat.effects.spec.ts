import {TestBed} from "@angular/core/testing";
import {HttpErrorResponse} from "@angular/common/http";
import {TranslocoService} from "@jsverse/transloco";
import {provideMockActions} from "@ngrx/effects/testing";
import {MockStore, provideMockStore} from "@ngrx/store/testing";
import {Action} from "@ngrx/store";
import {Subject, throwError} from "rxjs";
import {vi} from "vitest";
import {ChatService} from "../../services/chat.service";
import {ChatResponse, ChatStreamEvent, Conversation} from "../../models/chat.model";
import * as AuthActions from "../auth/auth.actions";
import * as ChatActions from "./chat.actions";
import * as ChatSelectors from "./chat.selectors";
import * as AccountSelectors from "../account/account.selectors";
import {Account} from "../../models/account.model";
import {ChatEffects} from "./chat.effects";
import {translocoTesting} from "../../i18n/i18n.testing";

describe("ChatEffects", () => {
    const response = {conversation: {id: 7}, question: null, answer: {id: 11}, sources: []} as unknown as ChatResponse;
    const ask = ChatActions.sendMessage({
        request: {conversationId: null, modelId: 1, message: "Wann ist Inventur?", templateId: null},
    });

    function setup(selectedId: number | null = null, answersThroughAgent = false) {
        const actions$ = new Subject<Action>();
        const streams: Subject<ChatStreamEvent>[] = [];
        const conversations = new Subject<Conversation[]>();
        const chatService = {
            stream: vi.fn(() => {
                const stream = new Subject<ChatStreamEvent>();
                streams.push(stream);
                return stream;
            }),
            getConversations: vi.fn(() => conversations),
        };
        TestBed.configureTestingModule({
            imports: [translocoTesting("de")],
            providers: [
                ChatEffects,
                provideMockActions(() => actions$),
                provideMockStore(),
                {provide: ChatService, useValue: chatService},
            ],
        });
        TestBed.inject(MockStore).overrideSelector(ChatSelectors.selectSelectedConversationId, selectedId);
        TestBed.inject(MockStore).overrideSelector(AccountSelectors.selectAccount, {
            answersThroughAgent,
        } as Account);
        const effects = TestBed.inject(ChatEffects);
        const emitted: Action[] = [];
        effects.sendMessage$.subscribe(action => emitted.push(action));
        effects.stoppedAnswerStored$.subscribe(action => emitted.push(action));
        effects.loadConversations$.subscribe(action => emitted.push(action));
        effects.reloadAfterStoppedAnswerFailed$.subscribe(action => emitted.push(action));
        effects.reloadAfterRefusedDecision$.subscribe(action => emitted.push(action));
        effects.reloadAfterLostAnswer$.subscribe(action => emitted.push(action));
        return {actions$, streams, conversations, chatService, emitted};
    }

    it("a stopped answer ends at once - the next question may go - and shows once the backend stored it", () => {
        const {actions$, streams, chatService, emitted} = setup();
        actions$.next(ask);
        streams[0].next({type: "delta", text: "Ab "});

        actions$.next(ChatActions.stopStreaming());
        streams[0].next({type: "delta", text: "15 Uhr."});
        actions$.next(ask);
        streams[0].next({type: "done", response});

        expect(chatService.stream).toHaveBeenCalledTimes(2);
        expect(emitted).toEqual([
            ChatActions.streamDelta({text: "Ab "}),
            ChatActions.stoppedAnswerStored({askedIn: null, response}),
        ]);
    });

    it("a stopped answer of the tenant's agent closes the stream - the backend cancels the agent's task", () => {
        const {actions$, streams, emitted} = setup(null, true);
        actions$.next(ask);
        streams[0].next({type: "delta", text: "Ab "});

        actions$.next(ChatActions.stopStreaming());

        expect(streams[0].observed).toBe(false);
        expect(emitted).toEqual([ChatActions.streamDelta({text: "Ab "})]);
    });

    it("a stopped answer that fails tells so", () => {
        const {actions$, streams, emitted} = setup();
        actions$.next(ChatActions.sendMessage({request: {...ask.request, conversationId: 7}}));
        actions$.next(ChatActions.stopStreaming());

        streams[0].next({type: "error", message: "The model's provider did not answer"});

        expect(emitted).toEqual([
            ChatActions.stoppedAnswerFailed({askedIn: 7, error: "The model's provider did not answer"}),
        ]);
    });

    it("a stopped answer that failed reads the conversation it was asked in again - when it is still open", () => {
        const {actions$, emitted} = setup(7);

        actions$.next(ChatActions.stoppedAnswerFailed({askedIn: 7, error: "failed"}));
        actions$.next(ChatActions.stoppedAnswerFailed({askedIn: 8, error: "failed"}));
        actions$.next(ChatActions.stoppedAnswerFailed({askedIn: null, error: "failed"}));

        expect(emitted).toEqual([ChatActions.selectConversation({id: 7})]);
    });

    it("a refused decision reads the open conversation again - not another one", () => {
        const {actions$, emitted} = setup(7);

        actions$.next(ChatActions.decideActionFailure({conversationId: 7, error: "already decided"}));
        actions$.next(ChatActions.decideActionFailure({conversationId: 8, error: "already decided"}));

        expect(emitted).toEqual([ChatActions.selectConversation({id: 7})]);
    });

    it("a failed or lost answer reads the conversations and the open one again", () => {
        const open = setup(7);
        open.actions$.next(ChatActions.sendMessageFailure({error: "lost"}));
        expect(open.emitted).toEqual([ChatActions.loadConversations(), ChatActions.selectConversation({id: 7})]);
    });

    it("a failed or lost answer in a new chat reads the conversations again", () => {
        const {actions$, emitted} = setup();
        actions$.next(ChatActions.sendMessageFailure({error: "lost"}));
        expect(emitted).toEqual([ChatActions.loadConversations()]);
    });

    it("a tool step does not end the stream - the answer still comes", () => {
        const {actions$, streams, emitted} = setup();
        const step = {name: "similarity_search", software: null};
        actions$.next(ask);
        streams[0].next({type: "tool", step});
        streams[0].next({type: "delta", text: "Ab 15 Uhr."});
        streams[0].next({type: "done", response});

        expect(emitted.map(action => action.type)).toEqual([
            ChatActions.streamTool.type,
            ChatActions.streamDelta.type,
            ChatActions.sendMessageSuccess.type,
        ]);
    });

    it("a stream that ends without done or error leaves no answer pending", () => {
        const {actions$, streams, emitted} = setup();
        actions$.next(ask);
        streams[0].complete();

        expect(emitted.map(action => action.type)).toEqual([ChatActions.sendMessageFailure.type]);
    });

    it("a connection torn off mid-answer says the answer may still be stored", () => {
        const {actions$, streams, emitted} = setup();
        actions$.next(ask);
        streams[0].next({type: "delta", text: "Ab "});
        streams[0].error(new HttpErrorResponse({status: 0}));

        const failure = emitted.find(action => action.type === ChatActions.sendMessageFailure.type);
        expect(failure).toEqual(
            ChatActions.sendMessageFailure({
                error: TestBed.inject(TranslocoService).translate("errors.chat.connectionLost"),
            }),
        );
    });

    it("a logout ends a stopped answer's silent reading and a list still on its way", () => {
        const {actions$, streams, conversations, emitted} = setup();
        actions$.next(ask);
        actions$.next(ChatActions.stopStreaming());
        actions$.next(ChatActions.loadConversations());

        actions$.next(AuthActions.logout());
        streams[0].next({type: "done", response});
        conversations.next([response.conversation]);

        expect(emitted).toEqual([]);
    });

    describe("a failed upload reloads what it may have stored - a timeout comes while the backend still reads", () => {
        function failedUpload(conversationId: number | null): Action[] {
            const actions$ = new Subject<Action>();
            TestBed.configureTestingModule({
                imports: [translocoTesting("de")],
                providers: [
                    ChatEffects,
                    provideMockActions(() => actions$),
                    provideMockStore(),
                    {
                        provide: ChatService,
                        useValue: {uploadFiles: vi.fn(() => throwError(() => new HttpErrorResponse({status: 0})))},
                    },
                ],
            });
            const emitted: Action[] = [];
            TestBed.inject(ChatEffects).uploadFiles$.subscribe(action => emitted.push(action));
            actions$.next(ChatActions.uploadFiles({conversationId, files: [new File(["x"], "aufnahme.mp3")]}));
            return emitted;
        }

        it("the open conversation's files", () => {
            expect(failedUpload(7)[1]).toEqual(ChatActions.loadFiles({conversationId: 7}));
        });

        it("the conversations, for one the upload started", () => {
            expect(failedUpload(null)[1]).toEqual(ChatActions.loadConversations());
        });
    });
});
