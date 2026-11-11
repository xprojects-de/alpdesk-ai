import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {Branding, TenantBranding, TenantBrandingRequest} from "../models/branding.model";
import {environment} from "../../environments/environment";

/**
 * The look of the frontend: the installation's and a tenant's on its own login page (both anonymous), the logged-in
 * user's tenant's, and a tenant's as the admin keeps it. Logos behind a login come as blobs - an <img src> would not
 * carry the token.
 */
@Injectable({
    providedIn: "root",
})
export class BrandingService {
    private http = inject(HttpClient);

    /** The installation's logo, anonymous - usable as <img src>. */
    readonly installationLogoUrl = `${environment.apiUrl}/branding/logo`;

    getInstallation(): Observable<Branding> {
        return this.http.get<Branding>(`${environment.apiUrl}/branding`);
    }

    /** A tenant's look on its own login page; 404 for a key nobody has (any more). */
    getLogin(loginKey: string): Observable<Branding> {
        return this.http.get<Branding>(this.loginUrl(loginKey));
    }

    /** The logo of a tenant's own login page, anonymous - usable as <img src>. */
    loginLogoUrl(loginKey: string): string {
        return `${this.loginUrl(loginKey)}/logo`;
    }

    /** The key of the tenant admin's own tenant's login page. */
    getOwnLoginKey(): Observable<{loginKey: string}> {
        return this.http.get<{loginKey: string}>(`${environment.apiUrl}/tenant/admin/branding/login-key`);
    }

    getTenant(): Observable<Branding> {
        return this.http.get<Branding>(`${environment.apiUrl}/tenant/branding`);
    }

    getTenantLogo(): Observable<Blob> {
        return this.http.get(`${environment.apiUrl}/tenant/branding/logo`, {responseType: "blob"});
    }

    getForTenant(tenantId: number): Observable<TenantBranding> {
        return this.http.get<TenantBranding>(this.adminUrl(tenantId));
    }

    saveForTenant(tenantId: number, request: TenantBrandingRequest): Observable<TenantBranding> {
        return this.http.put<TenantBranding>(this.adminUrl(tenantId), request);
    }

    getLogoOfTenant(tenantId: number): Observable<Blob> {
        return this.http.get(`${this.adminUrl(tenantId)}/logo`, {responseType: "blob"});
    }

    uploadLogoOfTenant(tenantId: number, file: File): Observable<TenantBranding> {
        const body = new FormData();
        body.append("file", file, file.name);
        return this.http.put<TenantBranding>(`${this.adminUrl(tenantId)}/logo`, body);
    }

    deleteLogoOfTenant(tenantId: number): Observable<void> {
        return this.http.delete<void>(`${this.adminUrl(tenantId)}/logo`);
    }

    /** A new key for the tenant's login page - the old address shows the neutral page from now on. */
    renewLoginKeyOfTenant(tenantId: number): Observable<TenantBranding> {
        return this.http.post<TenantBranding>(`${this.adminUrl(tenantId)}/login-key`, null);
    }

    private loginUrl(loginKey: string): string {
        return `${environment.apiUrl}/branding/login/${encodeURIComponent(loginKey)}`;
    }

    private adminUrl(tenantId: number): string {
        return `${environment.apiUrl}/admin/tenants/${tenantId}/branding`;
    }
}
