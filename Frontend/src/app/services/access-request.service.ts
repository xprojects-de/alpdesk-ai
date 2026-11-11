import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {AccessAssignment, AccessRequest} from "../models/access-request.model";
import {TenantUser} from "../models/tenant-user.model";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class AccessRequestService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/admin/access-requests`;

    getAll(): Observable<AccessRequest[]> {
        return this.http.get<AccessRequest[]>(this.apiUrl);
    }

    /** Makes the request a user of the tenant - without a password, it signs in through the WAM proxy. */
    assign(id: number, assignment: AccessAssignment): Observable<TenantUser> {
        return this.http.post<TenantUser>(`${this.apiUrl}/${id}/assign`, assignment);
    }

    reject(id: number): Observable<void> {
        return this.http.delete<void>(`${this.apiUrl}/${id}`);
    }
}
