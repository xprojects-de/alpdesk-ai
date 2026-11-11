import {inject, Injectable} from "@angular/core";
import {HttpClient, HttpParams} from "@angular/common/http";
import {Observable} from "rxjs";
import {ApplicationLogFilter, ApplicationLogResponse} from "../models/application-log.model";
import {environment} from "../../environments/environment";

/** The admin's view of the backend's recent warnings and errors, and the daily report sent at the admin's request. */
@Injectable({
    providedIn: "root",
})
export class ApplicationLogService {
    private http = inject(HttpClient);

    getRecent(filter: ApplicationLogFilter): Observable<ApplicationLogResponse> {
        let params = new HttpParams();
        if (filter.level) {
            params = params.set("level", filter.level);
        }
        if (filter.search.trim()) {
            params = params.set("search", filter.search.trim());
        }
        return this.http.get<ApplicationLogResponse>(`${environment.apiUrl}/admin/application-log`, {params});
    }

    /** A test of the mail settings: the daily report now, to its recipients. */
    sendDailyReport(): Observable<{recipients: number}> {
        return this.http.post<{recipients: number}>(`${environment.apiUrl}/admin/daily-report/send`, {});
    }
}
