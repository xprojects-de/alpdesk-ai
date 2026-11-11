import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {TenantUser, TenantUserRequest, UserFromTenantRequest} from "../models/tenant-user.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";
import {TenantScope} from "../models/tenant.model";

@Injectable({
    providedIn: "root",
})
export class TenantUserService {
    private http = inject(HttpClient);

    getAll(scope: TenantScope): Observable<TenantUser[]> {
        return this.http.get<TenantUser[]>(this.usersUrl(scope));
    }

    create(scope: TenantScope, user: TenantUserRequest): Observable<TenantUser> {
        return this.http.post<TenantUser>(this.usersUrl(scope), user);
    }

    /** The admin only - a tenant admin must not see another tenant's users. */
    addFromTenant(tenantId: number, request: UserFromTenantRequest): Observable<TenantUser> {
        return this.http.post<TenantUser>(`${this.usersUrl(tenantId)}/from-tenant`, request);
    }

    update(scope: TenantScope, userId: number, user: TenantUserRequest): Observable<TenantUser> {
        return this.http.put<TenantUser>(`${this.usersUrl(scope)}/${userId}`, user);
    }

    delete(scope: TenantScope, userId: number): Observable<void> {
        return this.http.delete<void>(`${this.usersUrl(scope)}/${userId}`);
    }

    private usersUrl(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/users`;
    }
}
