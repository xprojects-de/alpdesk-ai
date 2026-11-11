import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {FileStore, FileStoreCheck, FileStoreFolders, FileStoreRequest} from "../models/file-store.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

/** The tenant's file stores (SFTP, FTPS): the servers, their checks and confirmed fingerprints. */
@Injectable({
    providedIn: "root",
})
export class FileStoreService {
    private http = inject(HttpClient);

    list(scope: TenantScope): Observable<FileStore[]> {
        return this.http.get<FileStore[]>(this.url(scope));
    }

    create(scope: TenantScope, request: FileStoreRequest): Observable<FileStore> {
        return this.http.post<FileStore>(this.url(scope), request);
    }

    update(scope: TenantScope, storeId: number, request: FileStoreRequest): Observable<FileStore> {
        return this.http.put<FileStore>(`${this.url(scope)}/${storeId}`, request);
    }

    delete(scope: TenantScope, storeId: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${storeId}`);
    }

    /** Connects to the server - a failure is the answer's status, not an HTTP error. */
    check(scope: TenantScope, storeId: number): Observable<FileStoreCheck> {
        return this.http.post<FileStoreCheck>(`${this.url(scope)}/${storeId}/check`, null);
    }

    confirmFingerprint(scope: TenantScope, storeId: number, fingerprint: string): Observable<FileStore> {
        return this.http.put<FileStore>(`${this.url(scope)}/${storeId}/fingerprint`, {fingerprint});
    }

    /** The subfolders of a folder of the store (relative to its folder) - to choose one. */
    folders(scope: TenantScope, storeId: number, path: string): Observable<FileStoreFolders> {
        return this.http.get<FileStoreFolders>(`${this.url(scope)}/${storeId}/folders`, {params: {path}});
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/file-stores`;
    }
}
