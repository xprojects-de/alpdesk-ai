import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {map} from "rxjs/operators";
import {TenantModels} from "../models/tenant.model";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class TenantModelService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/admin/tenants`;

    getAssigned(tenantId: number): Observable<number[]> {
        return this.http.get<TenantModels>(`${this.apiUrl}/${tenantId}/models`).pipe(map(({modelIds}) => modelIds));
    }

    /** Replaces the tenant's models; answers with the ids now assigned. */
    assign(tenantId: number, modelIds: number[]): Observable<number[]> {
        return this.http
            .put<TenantModels>(`${this.apiUrl}/${tenantId}/models`, {modelIds})
            .pipe(map(response => response.modelIds));
    }
}
