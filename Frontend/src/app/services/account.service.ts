import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {Account, PasswordChangeRequest} from "../models/account.model";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class AccountService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/tenant/account`;

    get(): Observable<Account> {
        return this.http.get<Account>(this.apiUrl);
    }

    changePassword(request: PasswordChangeRequest): Observable<void> {
        return this.http.put<void>(`${this.apiUrl}/password`, request);
    }

    changeLanguage(language: string): Observable<void> {
        return this.http.put<void>(`${this.apiUrl}/language`, {language});
    }
}
