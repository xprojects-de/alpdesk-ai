import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {
    McpAccess,
    McpConnection,
    McpConnectionRequest,
    McpPrompt,
    McpTool,
    McpConnectionCheck,
} from "../models/mcp.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

/** The tenant's connected software (MCP clients): connections, their tools and each user's level. */
@Injectable({
    providedIn: "root",
})
export class McpConnectionService {
    private http = inject(HttpClient);

    list(scope: TenantScope): Observable<McpConnection[]> {
        return this.http.get<McpConnection[]>(this.url(scope));
    }

    /** Reads the software's tools at once - all off; toolsError says why when it cannot. */
    create(scope: TenantScope, request: McpConnectionRequest): Observable<McpConnection> {
        return this.http.post<McpConnection>(this.url(scope), request);
    }

    update(scope: TenantScope, connectionId: number, request: McpConnectionRequest): Observable<McpConnection> {
        return this.http.put<McpConnection>(`${this.url(scope)}/${connectionId}`, request);
    }

    /** Starts the OAuth sign-in: the server's sign-in page the browser goes to; it comes back to returnUrl. */
    signIn(scope: TenantScope, connectionId: number, returnUrl: string): Observable<{authorizationUrl: string}> {
        return this.http.post<{authorizationUrl: string}>(`${this.url(scope)}/${connectionId}/sign-in`, {returnUrl});
    }

    /** Completes the sign-in with the server's answer the browser came back with - for the one who started it. */
    completeSignIn(scope: TenantScope, connectionId: number, callback: string): Observable<McpConnection> {
        return this.http.post<McpConnection>(`${this.url(scope)}/${connectionId}/sign-in/complete`, {callback});
    }

    removeSignIn(scope: TenantScope, connectionId: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${connectionId}/sign-in`);
    }

    delete(scope: TenantScope, connectionId: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${connectionId}`);
    }

    check(scope: TenantScope, connectionId: number): Observable<McpConnectionCheck> {
        return this.http.post<McpConnectionCheck>(`${this.url(scope)}/${connectionId}/check`, null);
    }

    reload(scope: TenantScope, connectionId: number): Observable<McpConnection> {
        return this.http.post<McpConnection>(`${this.url(scope)}/${connectionId}/reload`, null);
    }

    updateTool(
        scope: TenantScope,
        connectionId: number,
        toolId: number,
        change: {enabled?: boolean; changes?: boolean},
    ): Observable<McpTool> {
        return this.http.put<McpTool>(`${this.url(scope)}/${connectionId}/tools/${toolId}`, change);
    }

    updatePrompt(scope: TenantScope, connectionId: number, promptId: number, enabled: boolean): Observable<McpPrompt> {
        return this.http.put<McpPrompt>(`${this.url(scope)}/${connectionId}/prompts/${promptId}`, {enabled});
    }

    /** Whether MCP keys with the connection get it directly ("Direkt an MCP-Clients"), or its answers only. */
    setPassThrough(scope: TenantScope, connectionId: number, passesThrough: boolean): Observable<McpConnection> {
        return this.http.put<McpConnection>(`${this.url(scope)}/${connectionId}/pass-through`, {passesThrough});
    }

    /** Whether MCP keys that pass the connection through read its resources ("Ressourcen an MCP-Clients"). */
    setPassResources(scope: TenantScope, connectionId: number, passesResources: boolean): Observable<McpConnection> {
        return this.http.put<McpConnection>(`${this.url(scope)}/${connectionId}/resources`, {passesResources});
    }

    access(scope: TenantScope, connectionId: number): Observable<McpAccess[]> {
        return this.http.get<McpAccess[]>(`${this.url(scope)}/${connectionId}/access`);
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/mcp-connections`;
    }
}
