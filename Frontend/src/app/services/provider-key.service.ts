import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {AiProvider, ProviderKey} from "../models/ai.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

/** "global": the admin's keys for all tenants allowed to use them; else a tenant's own keys (TenantScope). */
export type ProviderKeyScope = "global" | TenantScope;

@Injectable({
    providedIn: "root",
})
export class ProviderKeyService {
    private http = inject(HttpClient);

    getAll(scope: ProviderKeyScope): Observable<ProviderKey[]> {
        return this.http.get<ProviderKey[]>(this.keysUrl(scope));
    }

    set(scope: ProviderKeyScope, provider: AiProvider, apiKey: string): Observable<ProviderKey> {
        return this.http.put<ProviderKey>(`${this.keysUrl(scope)}/${provider}`, {apiKey});
    }

    remove(scope: ProviderKeyScope, provider: AiProvider): Observable<void> {
        return this.http.delete<void>(`${this.keysUrl(scope)}/${provider}`);
    }

    private keysUrl(scope: ProviderKeyScope): string {
        return scope === "global"
            ? `${environment.apiUrl}/admin/provider-keys`
            : `${tenantSettingsUrl(environment.apiUrl, scope)}/provider-keys`;
    }
}
