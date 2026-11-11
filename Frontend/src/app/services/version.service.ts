import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {VersionInfo} from "../models/version.model";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class VersionService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/version`;

    get(): Observable<VersionInfo> {
        return this.http.get<VersionInfo>(this.apiUrl);
    }
}
