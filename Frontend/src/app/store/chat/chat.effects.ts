import {inject, Injectable} from "@angular/core";
import {HttpErrorResponse} from "@angular/common/http";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {Action, Store} from "@ngrx/store";
import {EMPTY, Observable, of, Subject} from "rxjs";
import {
    catchError,
    concatMap,
    endWith,
    exhaustMap,
    filter,
    map,
    mergeMap,
    share,
    switchMap,
    takeUntil,
    takeWhile,
    tap,
    withLatestFrom,
} from "rxjs/operators";
import {saveBlob} from "../../utils/download.util";
import {releasedPreviewUrls} from "../../utils/chat-image.util";
import * as AuthActions from "../auth/auth.actions";
import {ChatService} from "../../services/chat.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import {TranslocoService} from "@jsverse/transloco";
import {ChatResponse, ChatStreamEvent} from "../../models/chat.model";
import * as ChatActions from "./chat.actions";
import * as ChatSelectors from "./chat.selectors";
import * as AccountSelectors from "../account/account.selectors";
import {endedByLogout} from "../session.operator";
import {conversationToReopen} from "../../utils/chat.util";

@Injectable()
export class ChatEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private transloco = inject(TranslocoService);
    private chatService = inject(ChatService);
    private store = inject(Store);
    /** The object URLs of image previews not released yet. */
    private previewUrls: string[] = [];
    /** The rest of a stopped answer's stream, read silently until the backend stored it. */
    private stoppedAnswers = new Subject<{
        askedIn: number | null;
        events$: Observable<ChatStreamEvent>;
        fallbackKey: string;
    }>();

    loadConversations$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.loadConversations),
            switchMap(() =>
                this.chatService.getConversations().pipe(
                    map(conversations => ChatActions.loadConversationsSuccess({conversations})),
                    catchError(error =>
                        of(
                            ChatActions.loadConversationsFailure({
                                error: this.errors.of(error, "errors.chat.loadConversations"),
                            }),
                        ),
                    ),
                ),
            ),
            // A list asked for before a logout must not fill the next user's.
            endedByLogout(this.actions$),
        ),
    );

    loadTemplates$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.loadTemplates),
            switchMap(() =>
                this.chatService.getTemplates().pipe(
                    map(templates => ChatActions.loadTemplatesSuccess({templates})),
                    catchError(error =>
                        of(
                            ChatActions.loadTemplatesFailure({
                                error: this.errors.of(error, "errors.chat.loadTemplates"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    loadSoftware$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.loadSoftware),
            switchMap(() =>
                this.chatService.getSoftware().pipe(
                    map(software => ChatActions.loadSoftwareSuccess({software})),
                    catchError(error =>
                        of(ChatActions.loadSoftwareFailure({error: this.errors.of(error, "errors.chat.loadSoftware")})),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    loadSoftwarePrompts$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.loadSoftwarePrompts),
            switchMap(() =>
                this.chatService.getSoftwarePrompts().pipe(
                    map(prompts => ChatActions.loadSoftwarePromptsSuccess({prompts})),
                    catchError(error =>
                        of(
                            ChatActions.loadSoftwarePromptsFailure({
                                error: this.errors.of(error, "errors.chat.loadSoftwarePrompts"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    fillSoftwarePrompt$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.fillSoftwarePrompt),
            switchMap(({promptId, values}) =>
                this.chatService.fillSoftwarePrompt(promptId, values).pipe(
                    map(({text}) => ChatActions.fillSoftwarePromptSuccess({text})),
                    catchError(error =>
                        of(
                            ChatActions.fillSoftwarePromptFailure({
                                error: this.errors.of(error, "errors.chat.fillSoftwarePrompt"),
                            }),
                        ),
                    ),
                ),
            ),
            // The text must not land in the next user's input field.
            endedByLogout(this.actions$),
        ),
    );

    loadMessages$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.selectConversation),
            switchMap(({id}) =>
                this.chatService.getMessages(id).pipe(
                    map(messages => ChatActions.loadMessagesSuccess({conversationId: id, messages})),
                    catchError(error =>
                        of(
                            ChatActions.loadMessagesFailure({
                                error: this.errors.of(error, "errors.chat.loadMessages"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    // exhaustMap: one question at a time - the page does not send while an answer is streaming.
    sendMessage$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.sendMessage),
            withLatestFrom(this.store.select(AccountSelectors.selectAccount)),
            exhaustMap(([{request}, account]) =>
                this.answer(
                    this.chatService.stream(request),
                    request.conversationId,
                    response => ChatActions.sendMessageSuccess({conversationId: request.conversationId, response}),
                    error => ChatActions.sendMessageFailure({error}),
                    "errors.chat.sendMessage",
                    account?.answersThroughAgent ?? false,
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    decideAction$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.decideAction),
            exhaustMap(({conversationId, actionId, decision}) =>
                this.answer(
                    this.chatService.decide(actionId, decision),
                    conversationId,
                    response => ChatActions.decideActionSuccess({conversationId, actionId, decision, response}),
                    error => ChatActions.decideActionFailure({conversationId, error}),
                    decision === "run" ? "errors.chat.runAction" : "errors.chat.cancelAction",
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    // Decided or expired meanwhile (another tab, a day passed): the open conversation shows the calls as they are now.
    reloadAfterRefusedDecision$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.decideActionFailure),
            withLatestFrom(this.store.select(ChatSelectors.selectSelectedConversationId)),
            filter(([{conversationId}, selectedId]) => conversationId === selectedId),
            map(([{conversationId}]) => ChatActions.selectConversation({id: conversationId})),
        ),
    );

    /**
     * An answer as it streams in: its sources and pieces, then the success or failure action. A stream that ends
     * without "done" or "error" (connection lost) must not leave the answer pending. Stopped, it ends at once - the next
     * question may go - and its rest is read silently (stoppedAnswers), so the answer shows once the backend stored it;
     * closedByStop (a tenant answering through its agent): the stream is closed instead, nothing is stored.
     */
    private answer(
        events$: Observable<ChatStreamEvent>,
        askedIn: number | null,
        success: (response: ChatResponse) => Action,
        failure: (error: string) => Action,
        fallbackKey: string,
        closedByStop = false,
    ): Observable<Action> {
        // Shared: the silent reader takes the stream over before the answer lets go of it.
        const shared$ = events$.pipe(share());
        // An agent's answer is not read on: the closed stream stops it, and the backend cancels the agent's task.
        const stopped$ = this.actions$.pipe(
            ofType(ChatActions.stopStreaming),
            tap(() => {
                if (!closedByStop) {
                    this.stoppedAnswers.next({askedIn, events$: shared$, fallbackKey});
                }
            }),
        );
        return shared$.pipe(
            map(event => {
                switch (event.type) {
                    case "sources":
                        return ChatActions.streamSources({sources: event.sources});
                    case "delta":
                        return ChatActions.streamDelta({text: event.text});
                    case "thinking":
                        return ChatActions.streamThinking({text: event.text});
                    case "tool":
                        return ChatActions.streamTool({step: event.step});
                    case "done":
                        return success(event.response);
                    case "error":
                        return failure(this.errors.ofBody(event, fallbackKey));
                }
            }),
            // After a last event takeWhile has already completed, so this never follows it.
            endWith(failure(this.transloco.translate("errors.chat.connectionLost"))),
            takeWhile(
                action =>
                    action.type === ChatActions.streamSources.type ||
                    action.type === ChatActions.streamDelta.type ||
                    action.type === ChatActions.streamThinking.type ||
                    action.type === ChatActions.streamTool.type,
                true,
            ),
            takeUntil(stopped$),
            catchError(error => of(failure(this.answerError(error, fallbackKey)))),
        );
    }

    // A connection torn off by a proxy or the network ends with status 0 - the backend may still store the answer.
    private answerError(error: unknown, fallbackKey: string): string {
        if (error instanceof HttpErrorResponse && error.status === 0) {
            return this.transloco.translate("errors.chat.connectionLost");
        }
        return this.errors.of(error, fallbackKey);
    }

    // mergeMap: a stopped answer may still be on its way when the next one is stopped.
    stoppedAnswerStored$ = createEffect(() =>
        this.stoppedAnswers.pipe(
            mergeMap(({askedIn, events$, fallbackKey}) =>
                events$.pipe(
                    filter(
                        (event): event is Extract<ChatStreamEvent, {type: "done" | "error"}> =>
                            event.type === "done" || event.type === "error",
                    ),
                    map(event =>
                        event.type === "done"
                            ? ChatActions.stoppedAnswerStored({askedIn, response: event.response})
                            : ChatActions.stoppedAnswerFailed({askedIn, error: this.errors.ofBody(event, fallbackKey)}),
                    ),
                    // The connection lost: the backend still stores the answer, the next load shows it.
                    catchError(() => EMPTY),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    // A stopped decision that failed leaves its call run or cancelled: the open conversation shows it as it is now.
    reloadAfterStoppedAnswerFailed$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.stoppedAnswerFailed),
            withLatestFrom(this.store.select(ChatSelectors.selectSelectedConversationId)),
            filter(([{askedIn}, selectedId]) => askedIn !== null && askedIn === selectedId),
            map(([{askedIn}]) => ChatActions.selectConversation({id: askedIn as number})),
        ),
    );

    // The stored answer shows where the user still is: the conversation shown, or the new chat it started.
    reopenAfterStoppedAnswer$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.stoppedAnswerStored),
            withLatestFrom(
                this.store.select(ChatSelectors.selectSelectedConversationId),
                this.store.select(ChatSelectors.selectSending),
            ),
            map(([{askedIn, response}, selectedId, sending]) =>
                conversationToReopen(response.conversation.id, askedIn, selectedId, sending),
            ),
            filter((id): id is number => id !== null),
            map(id => ChatActions.selectConversation({id})),
        ),
    );

    deleteConversation$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.deleteConversation),
            concatMap(({id}) =>
                this.chatService.deleteConversation(id).pipe(
                    map(() => ChatActions.deleteConversationSuccess({id})),
                    catchError(error =>
                        of(
                            ChatActions.deleteConversationFailure({
                                error: this.errors.of(error, "errors.chat.deleteConversation"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    // A failed answer, or the connection lost mid-answer: the conversations and the open one's messages are read again.
    // After a lost connection the backend answers and stores on all the same, as after a stop - but only once its
    // answer is complete, so this reload often comes too early; the message tells the user to look again.
    reloadAfterLostAnswer$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.sendMessageFailure),
            withLatestFrom(this.store.select(ChatSelectors.selectSelectedConversationId)),
            concatMap(([, selectedId]) =>
                selectedId == null
                    ? [ChatActions.loadConversations()]
                    : [ChatActions.loadConversations(), ChatActions.selectConversation({id: selectedId})],
            ),
        ),
    );

    // Opening a conversation loads its files too.
    loadFilesOnOpen$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.selectConversation),
            map(({id}) => ChatActions.loadFiles({conversationId: id})),
        ),
    );

    // An answer that created files adds them to the conversation's files - only while it is open: loading another's
    // would cancel the files of the one just opened (switchMap below).
    loadFilesAfterAnswer$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.sendMessageSuccess, ChatActions.decideActionSuccess),
            filter(({response}) => response.answer.files.length > 0),
            withLatestFrom(this.store.select(ChatSelectors.selectSelectedConversationId)),
            filter(([{response}, selectedId]) => response.conversation.id === selectedId),
            map(([{response}]) => ChatActions.loadFiles({conversationId: response.conversation.id})),
        ),
    );

    loadFiles$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.loadFiles),
            switchMap(({conversationId}) =>
                this.chatService.getFiles(conversationId).pipe(
                    map(files => ChatActions.loadFilesSuccess({conversationId, files})),
                    catchError(error =>
                        of(
                            ChatActions.loadFilesFailure({
                                error: this.errors.of(error, "errors.chat.loadFiles"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    uploadFiles$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.uploadFiles),
            concatMap(({conversationId, files}) =>
                this.chatService.uploadFiles(conversationId, files).pipe(
                    map(response => ChatActions.uploadFilesSuccess({conversationId, response})),
                    // A timeout may come while the backend still reads the files (audio is transcribed first) and
                    // stores them after all: reloaded, a second try shows them instead of uploading them twice.
                    catchError(error =>
                        of(
                            ChatActions.uploadFilesFailure({
                                error: this.errors.of(error, "errors.chat.uploadFiles"),
                            }),
                            conversationId === null
                                ? ChatActions.loadConversations()
                                : ChatActions.loadFiles({conversationId}),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    deleteFile$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.deleteFile),
            concatMap(({id}) =>
                this.chatService.deleteFile(id).pipe(
                    map(() => ChatActions.deleteFileSuccess({id})),
                    catchError(error =>
                        of(
                            ChatActions.deleteFileFailure({
                                error: this.errors.of(error, "errors.chat.deleteFile"),
                            }),
                        ),
                    ),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    // mergeMap: several downloads may run side by side. Only failures are dispatched - a download is its own success.
    downloadFile$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.downloadFile),
            mergeMap(({file}) =>
                this.chatService.downloadFile(file.id).pipe(
                    mergeMap(blob => {
                        saveBlob(blob, file.fileName);
                        return of();
                    }),
                    catchError(error =>
                        of(
                            ChatActions.downloadFileFailure({
                                error: this.errors.of(error, "errors.chat.downloadFile"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    // mergeMap: an answer may show several images.
    loadImagePreview$ = createEffect(() =>
        this.actions$.pipe(
            ofType(ChatActions.loadImagePreview),
            mergeMap(({file}) =>
                this.chatService.downloadFile(file.id).pipe(
                    map(blob => {
                        const url = URL.createObjectURL(blob);
                        this.previewUrls.push(url);
                        return ChatActions.loadImagePreviewSuccess({fileId: file.id, url});
                    }),
                    catchError(() => of(ChatActions.imagePreviewFailure({fileId: file.id}))),
                ),
            ),
            endedByLogout(this.actions$),
        ),
    );

    /** An object URL keeps its image in memory: released once the store dropped it (another conversation, logout). */
    releaseImagePreviews$ = createEffect(
        () =>
            this.actions$.pipe(
                ofType(
                    ChatActions.selectConversation,
                    ChatActions.newConversation,
                    ChatActions.deleteConversationSuccess,
                    AuthActions.logoutSuccess,
                ),
                withLatestFrom(this.store.select(ChatSelectors.selectImagePreviews)),
                map(([, previews]) => {
                    const released = releasedPreviewUrls(this.previewUrls, previews);
                    released.forEach(url => URL.revokeObjectURL(url));
                    this.previewUrls = this.previewUrls.filter(url => !released.includes(url));
                }),
            ),
        {dispatch: false},
    );
}
