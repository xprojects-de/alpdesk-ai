import {ChatTemplateOption} from "../models/assistant.model";
import {Injectable, inject} from "@angular/core";
import {HttpClient, HttpErrorResponse, HttpEventType, HttpResponse} from "@angular/common/http";
import {defer, from, Observable, throwError} from "rxjs";
import {catchError, filter, map, mergeMap} from "rxjs/operators";
import {
    ActionDecision,
    ChatFile,
    ChatFileUploadResponse,
    ChatMessage,
    ChatRequest,
    ChatStreamEvent,
    Conversation,
    SoftwareChoice,
} from "../models/chat.model";
import {SoftwarePrompt} from "../models/mcp.model";
import {buildChatFileUpload} from "../utils/chat-file.util";
import {environment} from "../../environments/environment";
import {toChatStreamEvent} from "../utils/chat-stream.util";
import {SseEvent, SseReader} from "../utils/sse.util";

@Injectable({
    providedIn: "root",
})
export class ChatService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/tenant/chat`;

    /**
     * Asks a question and emits the answer's server-sent events as they arrive. Through HttpClient (not
     * EventSource, which cannot POST or send the token), so the interceptors apply: the XHR backend reports
     * the text received so far with every download progress event.
     */
    stream(request: ChatRequest): Observable<ChatStreamEvent> {
        return this.streamFrom(`${this.apiUrl}/stream`, request);
    }

    /** Runs or cancels a changing tool call the chat showed, and emits how the model goes on - as at stream. */
    decide(actionId: number, decision: ActionDecision): Observable<ChatStreamEvent> {
        return this.streamFrom(`${this.apiUrl}/actions/${actionId}/${decision}`, null);
    }

    private streamFrom(url: string, body: unknown): Observable<ChatStreamEvent> {
        return defer(() => {
            const reader = new SseReader();
            return this.http
                .post(url, body, {
                    // JSON too: without it, Micronaut cannot render a refusal before the stream (400/404/409) and
                    // answers a generic 500.
                    headers: {Accept: "text/event-stream, application/json"},
                    observe: "events",
                    reportProgress: true,
                    responseType: "text",
                })
                .pipe(
                    mergeMap(event => {
                        if (event.type === HttpEventType.DownloadProgress) {
                            return from(toStreamEvents(reader.read(event.partialText ?? "")));
                        }
                        if (event.type === HttpEventType.Response) {
                            return from(toStreamEvents(reader.finish(event.body ?? "")));
                        }
                        return from([]);
                    }),
                    catchError(error => throwError(() => withJsonBody(error))),
                );
        });
    }

    /** The templates of the tenant's assistant the user may choose - names only. */
    getTemplates(): Observable<ChatTemplateOption[]> {
        return this.http.get<ChatTemplateOption[]>(`${this.apiUrl}/templates`);
    }

    /** The connected software the user reaches - the topics a question may leave out. */
    getSoftware(): Observable<SoftwareChoice[]> {
        return this.http.get<SoftwareChoice[]>(`${this.apiUrl}/software`);
    }

    /** The prompts of the connected software the user may use. */
    getSoftwarePrompts(): Observable<SoftwarePrompt[]> {
        return this.http.get<SoftwarePrompt[]>(`${this.apiUrl}/prompts`);
    }

    /** The text the software makes of a prompt with these values - for the input field, nothing is sent. */
    fillSoftwarePrompt(promptId: number, values: Record<string, string>): Observable<{text: string}> {
        return this.http.post<{text: string}>(`${this.apiUrl}/prompts/${promptId}`, {arguments: values});
    }

    getConversations(): Observable<Conversation[]> {
        return this.http.get<Conversation[]>(`${this.apiUrl}/conversations`);
    }

    getMessages(conversationId: number): Observable<ChatMessage[]> {
        return this.http.get<ChatMessage[]>(`${this.apiUrl}/conversations/${conversationId}/messages`);
    }

    /**
     * Without a conversationId the backend starts a new conversation for the files. Reports its progress: a video of
     * up to 500 MB takes long over a slow uplink, and the timeout then counts from the last progress, not the start.
     */
    uploadFiles(conversationId: number | null, files: File[]): Observable<ChatFileUploadResponse> {
        return this.http
            .post<ChatFileUploadResponse>(`${this.apiUrl}/files`, buildChatFileUpload(files, conversationId), {
                observe: "events",
                reportProgress: true,
            })
            .pipe(
                filter(event => event.type === HttpEventType.Response),
                map(event => (event as HttpResponse<ChatFileUploadResponse>).body as ChatFileUploadResponse),
            );
    }

    getFiles(conversationId: number): Observable<ChatFile[]> {
        return this.http.get<ChatFile[]>(`${this.apiUrl}/conversations/${conversationId}/files`);
    }

    /**
     * Reports its progress like the upload: a large file takes long over a slow line, and the timeout then counts
     * from the last progress, not the start.
     */
    downloadFile(fileId: number): Observable<Blob> {
        return this.http
            .get(`${this.apiUrl}/files/${fileId}/content`, {
                responseType: "blob",
                observe: "events",
                reportProgress: true,
            })
            .pipe(
                filter(event => event.type === HttpEventType.Response),
                map(event => (event as HttpResponse<Blob>).body as Blob),
            );
    }

    deleteFile(fileId: number): Observable<void> {
        return this.http.delete<void>(`${this.apiUrl}/files/${fileId}`);
    }

    deleteConversation(conversationId: number): Observable<void> {
        return this.http.delete<void>(`${this.apiUrl}/conversations/${conversationId}`);
    }
}

function toStreamEvents(events: SseEvent[]): ChatStreamEvent[] {
    return events.map(toChatStreamEvent).filter((event): event is ChatStreamEvent => event !== null);
}

/**
 * A refusal before the stream (400, 404, 409) has a JSON body, but with responseType "text" it arrives as a
 * string - parsed so extractErrorMessage finds its message.
 */
function withJsonBody(error: unknown): unknown {
    if (!(error instanceof HttpErrorResponse) || typeof error.error !== "string") {
        return error;
    }
    try {
        return new HttpErrorResponse({
            error: JSON.parse(error.error),
            headers: error.headers,
            status: error.status,
            statusText: error.statusText,
            url: error.url ?? undefined,
        });
    } catch {
        return error;
    }
}
