import {TestBed} from "@angular/core/testing";
import {HttpEventType, provideHttpClient, withInterceptors} from "@angular/common/http";
import {HttpTestingController, provideHttpClientTesting} from "@angular/common/http/testing";
import {afterEach, beforeEach, vi} from "vitest";
import {timeoutInterceptor} from "../interceptors/timeout.interceptor";
import {ChatFileUploadResponse} from "../models/chat.model";
import {ChatService} from "./chat.service";

/**
 * A video of up to 500 MB takes long over a slow uplink: the chat's upload reports its progress, so the timeout counts
 * from the last progress - an upload that goes on is never cut off, one that stalls is.
 */
describe("ChatService.uploadFiles", () => {
    const MINUTE = 60000;
    const response = {conversation: {id: 3}, files: []} as unknown as ChatFileUploadResponse;

    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    function setup() {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(withInterceptors([timeoutInterceptor])), provideHttpClientTesting()],
        });
        return {chat: TestBed.inject(ChatService), backend: TestBed.inject(HttpTestingController)};
    }

    function progress(loaded: number) {
        return {type: HttpEventType.UploadProgress, loaded, total: 100} as const;
    }

    it("an upload that goes on for longer than the timeout still arrives - only its response comes out", () => {
        const {chat, backend} = setup();
        const results: ChatFileUploadResponse[] = [];
        const errors: unknown[] = [];
        chat.uploadFiles(3, [new File(["x"], "lauf.mp4")]).subscribe({
            next: result => results.push(result),
            error: error => errors.push(error),
        });

        const request = backend.expectOne(r => r.url.endsWith("/tenant/chat/files"));
        expect(request.request.reportProgress).toBe(true);
        for (let loaded = 10; loaded <= 90; loaded += 10) {
            vi.advanceTimersByTime(5 * MINUTE);
            request.event(progress(loaded));
        }
        request.flush(response);

        expect(errors).toEqual([]);
        expect(results).toEqual([response]);
    });

    it("an upload that stalls ends with the timeout", () => {
        const {chat, backend} = setup();
        const errors: {error?: {code?: string}}[] = [];
        chat.uploadFiles(null, [new File(["x"], "lauf.mp4")]).subscribe({error: error => errors.push(error)});

        const request = backend.expectOne(r => r.url.endsWith("/tenant/chat/files"));
        request.event(progress(10));
        vi.advanceTimersByTime(7 * MINUTE);

        expect(errors.map(error => error.error?.code)).toEqual(["request.timeout"]);
    });
});

/** A download reports its progress too: a large file over a slow line is not cut off after the plain timeout. */
describe("ChatService.downloadFile", () => {
    const SECOND = 1000;

    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    function setup() {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(withInterceptors([timeoutInterceptor])), provideHttpClientTesting()],
        });
        return {chat: TestBed.inject(ChatService), backend: TestBed.inject(HttpTestingController)};
    }

    function progress(loaded: number) {
        return {type: HttpEventType.DownloadProgress, loaded, total: 100} as const;
    }

    it("a download that goes on for longer than the timeout still arrives - only its content comes out", () => {
        const {chat, backend} = setup();
        const content = new Blob(["x"]);
        const results: Blob[] = [];
        const errors: unknown[] = [];
        chat.downloadFile(7).subscribe({next: blob => results.push(blob), error: error => errors.push(error)});

        const request = backend.expectOne(r => r.url.endsWith("/tenant/chat/files/7/content"));
        expect(request.request.reportProgress).toBe(true);
        for (let loaded = 10; loaded <= 90; loaded += 10) {
            vi.advanceTimersByTime(20 * SECOND);
            request.event(progress(loaded));
        }
        request.flush(content);

        expect(errors).toEqual([]);
        expect(results).toEqual([content]);
    });

    it("a download that stalls ends with the timeout", () => {
        const {chat, backend} = setup();
        const errors: {error?: {code?: string}}[] = [];
        chat.downloadFile(7).subscribe({error: error => errors.push(error)});

        const request = backend.expectOne(r => r.url.endsWith("/tenant/chat/files/7/content"));
        request.event(progress(10));
        vi.advanceTimersByTime(31 * SECOND);

        expect(errors.map(error => error.error?.code)).toEqual(["request.timeout"]);
    });
});
