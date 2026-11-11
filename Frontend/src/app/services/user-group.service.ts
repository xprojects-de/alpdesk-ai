import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {UserGroup, UserGroupRequest} from "../models/user-group.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class UserGroupService {
    private http = inject(HttpClient);

    getAll(scope: TenantScope): Observable<UserGroup[]> {
        return this.http.get<UserGroup[]>(this.url(scope));
    }

    create(scope: TenantScope, group: UserGroupRequest): Observable<UserGroup> {
        return this.http.post<UserGroup>(this.url(scope), group);
    }

    update(scope: TenantScope, id: number, group: UserGroupRequest): Observable<UserGroup> {
        return this.http.put<UserGroup>(`${this.url(scope)}/${id}`, group);
    }

    delete(scope: TenantScope, id: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${id}`);
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/groups`;
    }
}
