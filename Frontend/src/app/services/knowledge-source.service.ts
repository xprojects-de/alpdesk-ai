import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {KnowledgeSource, KnowledgeSourceRequest, SourceFile} from "../models/knowledge-source.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

/** The tenant's knowledge sources: folders of its file stores synced into its knowledge, their runs and files. */
@Injectable({
    providedIn: "root",
})
export class KnowledgeSourceService {
    private http = inject(HttpClient);

    list(scope: TenantScope): Observable<KnowledgeSource[]> {
        return this.http.get<KnowledgeSource[]>(this.url(scope));
    }

    create(scope: TenantScope, request: KnowledgeSourceRequest): Observable<KnowledgeSource> {
        return this.http.post<KnowledgeSource>(this.url(scope), request);
    }

    update(scope: TenantScope, sourceId: number, request: KnowledgeSourceRequest): Observable<KnowledgeSource> {
        return this.http.put<KnowledgeSource>(`${this.url(scope)}/${sourceId}`, request);
    }

    /** keepDocuments: its documents stay as ordinary ones, no longer kept up to date. */
    delete(scope: TenantScope, sourceId: number, keepDocuments: boolean): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${sourceId}`, {params: {keepDocuments}});
    }

    /** A run by hand: everything open is read, in the background. */
    run(scope: TenantScope, sourceId: number): Observable<KnowledgeSource> {
        return this.http.post<KnowledgeSource>(`${this.url(scope)}/${sourceId}/run`, null);
    }

    cancel(scope: TenantScope, sourceId: number): Observable<void> {
        return this.http.post<void>(`${this.url(scope)}/${sourceId}/cancel`, null);
    }

    /** The files without a document - refused, or deleted by hand. */
    files(scope: TenantScope, sourceId: number): Observable<SourceFile[]> {
        return this.http.get<SourceFile[]>(`${this.url(scope)}/${sourceId}/files`);
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/knowledge-sources`;
    }
}
