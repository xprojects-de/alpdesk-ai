import {DOCUMENT, Injectable, inject} from "@angular/core";
import {HttpClient, HttpHeaders} from "@angular/common/http";
import {fromEvent, Observable} from "rxjs";
import {filter, map, tap} from "rxjs/operators";
import {LoginRequest, LoginResponse, WamStatus} from "../models/auth.model";
import {environment} from "../../environments/environment";
import {browserLanguage} from "../utils/language.util";

@Injectable({
    providedIn: "root",
})
export class AuthService {
    private http = inject(HttpClient);
    private document = inject(DOCUMENT);
    private readonly apiUrl = `${environment.apiUrl}/login`;
    private readonly TOKEN_KEY = "auth_token";
    private readonly USERNAME_KEY = "auth_username";
    private readonly ROLES_KEY = "auth_roles";
    /** Per tab: the user logged out on purpose - the WAM proxy would otherwise sign them in again at once. */
    private readonly SIGNED_OUT_KEY = "wam_signed_out";

    /** A tenant's own login page sends its key: it picks the tenant for a username that is in several. */
    login(credentials: LoginRequest): Observable<LoginResponse> {
        const {loginKey, ...body} = credentials;
        const headers = loginKey ? new HttpHeaders({"X-Login-Key": loginKey}) : undefined;
        return this.http.post<LoginResponse>(this.apiUrl, body, {headers}).pipe(
            tap(response => {
                const token = this.extractToken(response);
                if (token) {
                    this.setToken(token);
                    this.setUsername(credentials.username);
                    this.setRoles(response.roles ?? []);
                }
            }),
        );
    }

    wamStatus(): Observable<WamStatus> {
        return this.http.get<WamStatus>(`${this.apiUrl}/wam`);
    }

    /**
     * Signs in as the user the WAM proxy sends; 409 while that user waits for approval. A person with a user in
     * several tenants gets the tenants to choose from (no token) - or, with tenantId, is signed in there (404 for a
     * tenant where it has none). The browser's language goes with it: a user the sign-in creates speaks it.
     */
    wamLogin(tenantId?: number): Observable<LoginResponse> {
        const language = browserLanguage(this.document.defaultView?.navigator.languages ?? []);
        return this.http
            .post<LoginResponse>(`${this.apiUrl}/wam`, tenantId === undefined ? {language} : {tenantId, language})
            .pipe(tap(response => this.keep(response)));
    }

    /**
     * A token of the person's user in another tenant (POST /tenant/account/switch) - kept like a login's. Without
     * WAM the target user's password proves the person.
     */
    switchTenant(tenantId: number, password?: string): Observable<LoginResponse> {
        return this.http
            .post<LoginResponse>(`${environment.apiUrl}/tenant/account/switch`, {tenantId, password})
            .pipe(tap(response => this.keep(response)));
    }

    private keep(response: LoginResponse): void {
        const token = this.extractToken(response);
        if (token) {
            this.setToken(token);
            this.setUsername(response.username ?? "");
            this.setRoles(response.roles ?? []);
        }
    }

    markSignedOut(): void {
        this.writeSession(this.SIGNED_OUT_KEY, "true");
    }

    clearSignedOut(): void {
        this.writeSession(this.SIGNED_OUT_KEY, null);
    }

    isSignedOut(): boolean {
        try {
            return sessionStorage.getItem(this.SIGNED_OUT_KEY) === "true";
        } catch {
            return false;
        }
    }

    /**
     * The single place that knows which response field carries the token, so a login response
     * missing both can be told apart from one that has it - see auth.effects.ts's login$.
     */
    extractToken(response: LoginResponse): string | null {
        return response.access_token || response.token || null;
    }

    logout(): void {
        localStorage.removeItem(this.TOKEN_KEY);
        localStorage.removeItem(this.USERNAME_KEY);
        localStorage.removeItem(this.ROLES_KEY);
    }

    /**
     * Another tab of this browser signed in, out or switched to another tenant: the token every request of this tab
     * reads changed under it (a key null is the whole storage cleared).
     */
    sessionChangedInOtherTab(): Observable<void> {
        return fromEvent<StorageEvent>(window, "storage").pipe(
            filter(event => event.key === this.TOKEN_KEY || event.key === null),
            map(() => undefined),
        );
    }

    getToken(): string | null {
        return localStorage.getItem(this.TOKEN_KEY);
    }

    getUsername(): string | null {
        return localStorage.getItem(this.USERNAME_KEY);
    }

    /** Empty for a session stored before roles were kept, or with an unreadable value - such a session must log in again. */
    getRoles(): string[] {
        try {
            const roles: unknown = JSON.parse(localStorage.getItem(this.ROLES_KEY) ?? "[]");
            return Array.isArray(roles) ? roles.filter(role => typeof role === "string") : [];
        } catch {
            return [];
        }
    }

    isAuthenticated(): boolean {
        const token = this.getToken();
        if (!token) {
            return false;
        }
        return !this.isTokenExpired(token);
    }

    isTokenExpired(token: string): boolean {
        try {
            const payload = this.decodeToken(token);
            if (!payload.exp) {
                return false;
            }

            const expirationDate = payload.exp * 1000;
            return Date.now() >= expirationDate;
        } catch {
            return true;
        }
    }

    private decodeToken(token: string): {exp?: number; [key: string]: unknown} {
        try {
            const payload = token.split(".")[1];
            // JWT payloads are Base64URL (RFC 4648 §5): '-'/'_' instead of '+'/'/', no padding.
            // atob() only understands standard Base64 and throws on '-'/'_', so normalize first.
            const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
            const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
            const decodedPayload = decodeURIComponent(
                atob(padded)
                    .split("")
                    .map(c => "%" + c.charCodeAt(0).toString(16).padStart(2, "0"))
                    .join(""),
            );
            return JSON.parse(decodedPayload);
        } catch (error) {
            // Keeps the original decoding failure attached instead of swallowing it.
            throw new Error("Invalid token format", {cause: error});
        }
    }

    private writeSession(key: string, value: string | null): void {
        try {
            if (value === null) {
                sessionStorage.removeItem(key);
            } else {
                sessionStorage.setItem(key, value);
            }
        } catch {
            // Without session storage the WAM proxy signs the user in again after a logout - harmless.
        }
    }

    private setToken(token: string): void {
        localStorage.setItem(this.TOKEN_KEY, token);
    }

    private setUsername(username: string): void {
        localStorage.setItem(this.USERNAME_KEY, username);
    }

    private setRoles(roles: string[]): void {
        localStorage.setItem(this.ROLES_KEY, JSON.stringify(roles));
    }
}
