import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {
    ApiKey,
    ApiKeyCreated,
    ApiKeyRequest,
    SandboxCheck,
    SearchLanguage,
    Tenant,
    TenantRequest,
    TenantScope,
} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class TenantService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/admin/tenants`;

    getAll(): Observable<Tenant[]> {
        return this.http.get<Tenant[]>(this.apiUrl);
    }

    create(tenant: TenantRequest): Observable<Tenant> {
        return this.http.post<Tenant>(this.apiUrl, tenant);
    }

    update(id: number, tenant: TenantRequest): Observable<Tenant> {
        return this.http.put<Tenant>(`${this.apiUrl}/${id}`, tenant);
    }

    delete(id: number): Observable<void> {
        return this.http.delete<void>(`${this.apiUrl}/${id}`);
    }

    /** Checks every instance of the tenant's own sandbox with its token. */
    checkSandbox(tenantId: number): Observable<SandboxCheck> {
        return this.http.get<SandboxCheck>(`${this.apiUrl}/${tenantId}/sandbox`);
    }

    /** Changing it embeds every document of the tenant again. */
    setEmbeddingModel(tenantId: number, modelId: number): Observable<Tenant> {
        return this.http.put<Tenant>(`${this.apiUrl}/${tenantId}/embedding-model`, {modelId});
    }

    /** Switches the tenant's reranker on (a model's id) or off (null) - embeds nothing. */
    setRerankingModel(tenantId: number, modelId: number | null): Observable<Tenant> {
        return this.http.put<Tenant>(`${this.apiUrl}/${tenantId}/reranking-model`, {modelId});
    }

    /** Builds the tenant's full-text search again for the language - embeds nothing. */
    setSearchLanguage(tenantId: number, language: SearchLanguage): Observable<Tenant> {
        return this.http.put<Tenant>(`${this.apiUrl}/${tenantId}/search-language`, {language});
    }

    /** The client software's API keys - of the tenant admin's own tenant, or of a tenant the admin keeps. */
    getApiKeys(scope: TenantScope): Observable<ApiKey[]> {
        return this.http.get<ApiKey[]>(this.apiKeysUrl(scope));
    }

    createApiKey(scope: TenantScope, request: ApiKeyRequest): Observable<ApiKeyCreated> {
        return this.http.post<ApiKeyCreated>(this.apiKeysUrl(scope), request);
    }

    updateApiKey(scope: TenantScope, keyId: number, request: ApiKeyRequest): Observable<ApiKey> {
        return this.http.put<ApiKey>(`${this.apiKeysUrl(scope)}/${keyId}`, request);
    }

    revokeApiKey(scope: TenantScope, keyId: number): Observable<void> {
        return this.http.delete<void>(`${this.apiKeysUrl(scope)}/${keyId}`);
    }

    private apiKeysUrl(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/api-keys`;
    }
}
