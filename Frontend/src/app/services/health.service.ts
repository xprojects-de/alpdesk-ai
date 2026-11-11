import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {map} from "rxjs/operators";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class HealthService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/watchdog`;

    // There is no meaningful response body to decode - a successful HTTP status is the entire
    // signal. Errors (connection refused, DNS failure, timeout) propagate as a normal Observable
    // error for the caller to catch, they are not swallowed here.
    ping(): Observable<void> {
        return this.http.get(this.apiUrl, {responseType: "text"}).pipe(map(() => void 0));
    }
}
