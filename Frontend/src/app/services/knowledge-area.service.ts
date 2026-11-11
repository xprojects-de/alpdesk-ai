import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {KnowledgeArea} from "../models/knowledge-area.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class KnowledgeAreaService {
    private http = inject(HttpClient);

    getAll(scope: TenantScope): Observable<KnowledgeArea[]> {
        return this.http.get<KnowledgeArea[]>(this.url(scope));
    }

    create(scope: TenantScope, name: string): Observable<KnowledgeArea> {
        return this.http.post<KnowledgeArea>(this.url(scope), {name});
    }

    rename(scope: TenantScope, id: number, name: string): Observable<KnowledgeArea> {
        return this.http.put<KnowledgeArea>(`${this.url(scope)}/${id}`, {name});
    }

    delete(scope: TenantScope, id: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/${id}`);
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/knowledge-areas`;
    }
}
