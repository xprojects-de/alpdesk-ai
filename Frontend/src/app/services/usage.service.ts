import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {UsageRow} from "../models/ai.model";
import {environment} from "../../environments/environment";

/** "admin": every tenant's usage; "tenant": the logged-in tenant admin's tenant only. */
export type UsageScope = "admin" | "tenant";

@Injectable({
    providedIn: "root",
})
export class UsageService {
    private http = inject(HttpClient);

    /** `month` as YYYY-MM. */
    get(scope: UsageScope, month: string): Observable<UsageRow[]> {
        const url =
            scope === "admin" ? `${environment.apiUrl}/admin/usage` : `${environment.apiUrl}/tenant/admin/usage`;
        return this.http.get<UsageRow[]>(url, {params: {month}});
    }
}
