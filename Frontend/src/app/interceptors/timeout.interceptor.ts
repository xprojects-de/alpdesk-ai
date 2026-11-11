import {HttpErrorResponse, HttpInterceptorFn, HttpRequest} from "@angular/common/http";
import {throwError, TimeoutError} from "rxjs";
import {catchError, timeout} from "rxjs/operators";

// The backend can still stall (a busy database, a GC pause) - without this, a
// stalled request hangs the UI indefinitely with no dispatched error and no way to recover except
// reloading the page. 30s is generous enough for slow legitimate calls while still catching a
// genuinely stuck request. Uploads (multipart) get longer: a large document over a slow uplink
// easily takes more than 30s, and the requests do not report upload progress. A chat answer and
// changing the embedding model wait for the AI provider (the backend allows it app.ai.timeout, 5 minutes, per call),
// so they get the long timeout - a minute above it, so a call that takes its full time still ends with the backend's
// answer instead of a timeout here. Changing the embedding model also waits while a document is being embedded, and
// connecting software (or changing its address, or reloading its tools) waits for the software's MCP server - up to
// app.ai.timeout as well.
// The timeout counts from the last event, so a streamed answer only fails if it stalls that long - the stream names
// each tool before it runs ("tool"), so a round of slow tools still shows signs of life - and an upload that reports
// its progress (the ZIP archive, the chat's files with their videos) only if the upload stalls.
const REQUEST_TIMEOUT_MS = 30000;
const LONG_TIMEOUT_MS = 6 * 60000;

// Deciding a changing tool call streams how the model goes on, like a question.
const LONG_RUNNING = [
    {method: "POST", path: /\/tenant\/chat\/stream$/},
    {method: "POST", path: /\/tenant\/chat\/actions\/\d+\/(run|cancel)$/},
    {method: "PUT", path: /\/(tenant\/admin|admin\/tenants\/\d+)\/embedding-model$/},
    {method: "POST", path: /\/(tenant\/admin|admin\/tenants\/\d+)\/mcp-connections(\/\d+\/reload)?$/},
    {method: "PUT", path: /\/(tenant\/admin|admin\/tenants\/\d+)\/mcp-connections\/\d+$/},
    // Checking a file store waits for its server: connecting, the login and listing the folder.
    {method: "POST", path: /\/(tenant\/admin|admin\/tenants\/\d+)\/file-stores\/\d+\/check$/},
    // A task's draft waits for the model - up to three rounds within the backend's 5 minutes.
    {method: "POST", path: /\/(tenant\/admin|admin\/tenants\/\d+)\/tasks\/draft$/},
];

export function isLongRunning(req: HttpRequest<unknown>): boolean {
    if (req.body instanceof FormData) {
        return true;
    }
    const path = req.url.split("?")[0];
    return LONG_RUNNING.some(call => call.method === req.method && call.path.test(path));
}

export const timeoutInterceptor: HttpInterceptorFn = (req, next) => {
    return next(req).pipe(
        timeout(isLongRunning(req) ? LONG_TIMEOUT_MS : REQUEST_TIMEOUT_MS),
        catchError(error => {
            if (error instanceof TimeoutError) {
                // Re-thrown as an HttpErrorResponse (rather than the raw TimeoutError) so it flows through
                // extractErrorMessage like a backend error - its code is translated (errors.backend.request.timeout)
                // instead of falling back to error.message and leaking TimeoutError's text.
                return throwError(
                    () =>
                        new HttpErrorResponse({
                            error: {
                                message: "Timeout: the server did not answer in time",
                                code: "request.timeout",
                                params: {},
                            },
                            status: 0,
                            statusText: "Timeout",
                            url: req.url,
                        }),
                );
            }
            return throwError(() => error);
        }),
    );
};
