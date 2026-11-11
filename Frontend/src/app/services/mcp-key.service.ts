import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {McpKey, McpKeyCreated, McpKeyRequest} from "../models/mcp.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

/** The tenant's MCP keys - several, each with its name, knowledge areas and web search. */
@Injectable({
    providedIn: "root",
})
export class McpKeyService {
    private http = inject(HttpClient);

    list(scope: TenantScope): Observable<McpKey[]> {
        return this.http.get<McpKey[]>(this.url(scope));
    }

    create(scope: TenantScope, request: McpKeyRequest): Observable<McpKeyCreated> {
        return this.http.post<McpKeyCreated>(this.url(scope), request);
    }

    update(scope: TenantScope, keyId: number, request: McpKeyRequest): Observable<McpKey> {
        return this.http.put<McpKey>(`${this.url(scope)}/${keyId}`, request);
    }

    /** A new secret - the previous one stops working, name, areas and web search stay. */
    renew(scope: TenantScope, keyId: number): Observable<McpKeyCreated> {
        return this.http.post<McpKeyCreated>(`${this.url(scope)}/${keyId}/renew`, null);
    }

    delete(scope: TenantScope, keyId: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${keyId}`);
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/mcp-keys`;
    }
}
