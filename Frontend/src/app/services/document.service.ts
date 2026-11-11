import {Injectable, inject} from "@angular/core";
import {HttpClient, HttpEventType, HttpResponse} from "@angular/common/http";
import {Observable} from "rxjs";
import {filter, map} from "rxjs/operators";
import {ImportedFile, TenantDocument} from "../models/document.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class DocumentService {
    private http = inject(HttpClient);

    getAll(scope: TenantScope): Observable<TenantDocument[]> {
        return this.http.get<TenantDocument[]>(this.url(scope));
    }

    /** areaId: the area it goes into; null: the company knowledge. */
    upload(scope: TenantScope, file: File, areaId: number | null): Observable<TenantDocument> {
        const body = new FormData();
        body.append("file", file, file.name);
        if (areaId !== null) {
            body.append("areaId", String(areaId));
        }
        return this.http.post<TenantDocument>(this.url(scope), body);
    }

    /**
     * A ZIP archive - every file becomes a document under its path; imported again, changed files are replaced. Sent
     * with its upload progress: the request timeout counts from the last event, and an archive of hundreds of
     * megabytes takes longer than that to upload over an office line.
     */
    importArchive(scope: TenantScope, file: File, areaId: number | null): Observable<{files: ImportedFile[]}> {
        const body = new FormData();
        body.append("file", file, file.name);
        if (areaId !== null) {
            body.append("areaId", String(areaId));
        }
        return this.http
            .post<{files: ImportedFile[]}>(`${this.url(scope)}/archive`, body, {
                observe: "events",
                reportProgress: true,
            })
            .pipe(
                filter(event => event.type === HttpEventType.Response),
                map(event => (event as HttpResponse<{files: ImportedFile[]}>).body ?? {files: []}),
            );
    }

    retry(scope: TenantScope, id: number): Observable<TenantDocument> {
        return this.http.post<TenantDocument>(`${this.url(scope)}/${id}/retry`, null);
    }

    /** Moves the document to an area, or to the company knowledge (null) - nothing is embedded again. */
    moveToArea(scope: TenantScope, id: number, areaId: number | null): Observable<TenantDocument> {
        return this.http.put<TenantDocument>(`${this.url(scope)}/${id}/area`, {areaId});
    }

    /** Every document embedded again with the tenant's model, in the background: how many. */
    embedAllAgain(scope: TenantScope): Observable<{documents: number}> {
        return this.http.post<{documents: number}>(`${this.url(scope)}/embed-again`, null);
    }

    /**
     * Deletes every document uploaded into the company knowledge ("company") or an area (its id) - before its ZIP is
     * imported anew. The client software's documents stay.
     */
    deleteUploads(scope: TenantScope, area: string): Observable<{deleted: number}> {
        return this.http.delete<{deleted: number}>(`${this.url(scope)}/uploads`, {params: {area}});
    }

    delete(scope: TenantScope, id: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${id}`);
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/documents`;
    }
}
