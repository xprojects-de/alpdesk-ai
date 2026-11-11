import {Injectable, inject} from "@angular/core";
import {HttpClient, HttpParams} from "@angular/common/http";
import {Observable} from "rxjs";
import {
    Task,
    TaskDraft,
    TaskDraftModels,
    TaskDraftRequest,
    TaskRequest,
    TaskRunDetail,
    TaskRunSummary,
    TaskSummary,
    TaskValidation,
} from "../models/task.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

/** The tenant's scheduled tasks and their runs. */
@Injectable({
    providedIn: "root",
})
export class TaskService {
    private http = inject(HttpClient);

    list(scope: TenantScope): Observable<TaskSummary[]> {
        return this.http.get<TaskSummary[]>(this.url(scope));
    }

    get(scope: TenantScope, taskId: number): Observable<Task> {
        return this.http.get<Task>(`${this.url(scope)}/${taskId}`);
    }

    /** Checks a text without saving it: its mistakes with their lines, or its steps for the graph. */
    validate(scope: TenantScope, source: string): Observable<TaskValidation> {
        return this.http.post<TaskValidation>(`${this.url(scope)}/validate`, {source});
    }

    create(scope: TenantScope, request: TaskRequest): Observable<Task> {
        return this.http.post<Task>(this.url(scope), request);
    }

    update(scope: TenantScope, taskId: number, request: TaskRequest): Observable<Task> {
        return this.http.put<Task>(`${this.url(scope)}/${taskId}`, request);
    }

    delete(scope: TenantScope, taskId: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${taskId}`);
    }

    start(scope: TenantScope, taskId: number): Observable<{runId: number}> {
        return this.http.post<{runId: number}>(`${this.url(scope)}/${taskId}/runs`, null);
    }

    runs(scope: TenantScope, taskId: number, limit: number): Observable<TaskRunSummary[]> {
        const params = new HttpParams().set("limit", limit);
        return this.http.get<TaskRunSummary[]>(`${this.url(scope)}/${taskId}/runs`, {params});
    }

    run(scope: TenantScope, taskId: number, runId: number): Observable<TaskRunDetail> {
        return this.http.get<TaskRunDetail>(`${this.url(scope)}/${taskId}/runs/${runId}`);
    }

    cancel(scope: TenantScope, taskId: number, runId: number): Observable<void> {
        return this.http.post<void>(`${this.url(scope)}/${taskId}/runs/${runId}/cancel`, null);
    }

    deleteRun(scope: TenantScope, taskId: number, runId: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${taskId}/runs/${runId}`);
    }

    deleteRuns(scope: TenantScope, taskId: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${taskId}/runs`);
    }

    /** A draft by the tenant's chat model - checked, never saved; it may take minutes (the long timeout). */
    draft(scope: TenantScope, request: TaskDraftRequest): Observable<TaskDraft> {
        return this.http.post<TaskDraft>(`${this.url(scope)}/draft`, request);
    }

    draftModels(scope: TenantScope): Observable<TaskDraftModels> {
        return this.http.get<TaskDraftModels>(`${this.url(scope)}/draft-models`);
    }

    reference(scope: TenantScope, language: string): Observable<{markdown: string}> {
        return this.http.get<{markdown: string}>(`${this.url(scope)}/reference`, {params: {language}});
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/tasks`;
    }
}
