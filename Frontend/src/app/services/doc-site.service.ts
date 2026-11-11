import {Injectable, inject} from "@angular/core";
import {HttpClient, HttpEventType, HttpResponse} from "@angular/common/http";
import {Observable} from "rxjs";
import {filter, map} from "rxjs/operators";
import {DocSite, DocSiteList, DocSiteUpload} from "../models/doc-site.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class DocSiteService {
    private http = inject(HttpClient);

    getAll(scope: TenantScope): Observable<DocSiteList> {
        return this.http.get<DocSiteList>(this.url(scope));
    }

    /**
     * The ZIP replaces the website of the whole tenant (areaId null) or of an area. Sent with its upload progress: the
     * request timeout counts from the last event, and a large ZIP takes longer than that over an office line.
     */
    upload(scope: TenantScope, file: File, areaId: number | null): Observable<DocSiteUpload> {
        const body = new FormData();
        body.append("file", file, file.name);
        if (areaId !== null) {
            body.append("areaId", String(areaId));
        }
        return this.http.post<DocSiteUpload>(this.url(scope), body, {observe: "events", reportProgress: true}).pipe(
            filter(event => event.type === HttpEventType.Response),
            map(event => (event as HttpResponse<DocSiteUpload>).body as DocSiteUpload),
        );
    }

    delete(scope: TenantScope, id: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${id}`);
    }

    /** The websites the signed-in tenant user reads. */
    getReadable(): Observable<DocSite[]> {
        return this.http.get<DocSite[]>(`${environment.apiUrl}/tenant/doc-sites`);
    }

    /**
     * The website's address for the next hour - as a tenant user (scope null) or for a tenant's admins. Absolute,
     * for an iframe: it carries its right in the path, as an iframe sends no Authorization header.
     */
    address(scope: TenantScope | null, id: number): Observable<string> {
        const base = scope === null ? `${environment.apiUrl}/tenant/doc-sites` : this.url(scope);
        return this.http
            .post<{path: string}>(`${base}/${id}/address`, null)
            .pipe(map(response => `${environment.apiUrl}/${response.path}`));
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/doc-sites`;
    }
}
