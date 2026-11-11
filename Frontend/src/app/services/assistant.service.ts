import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {AssistantSettings, ChatTemplate, ChatTemplateRequest, ModelOption} from "../models/assistant.model";
import {TenantScope} from "../models/tenant.model";
import {tenantSettingsUrl} from "../utils/tenant-scope.util";
import {environment} from "../../environments/environment";

@Injectable({
    providedIn: "root",
})
export class AssistantService {
    private http = inject(HttpClient);

    getSettings(scope: TenantScope): Observable<AssistantSettings> {
        return this.http.get<AssistantSettings>(this.url(scope));
    }

    saveSettings(scope: TenantScope, settings: AssistantSettings): Observable<AssistantSettings> {
        return this.http.put<AssistantSettings>(this.url(scope), settings);
    }

    /** The chat models the tenant may use - to choose its default model from. */
    getModels(scope: TenantScope): Observable<ModelOption[]> {
        return this.http.get<ModelOption[]>(`${this.url(scope)}/models`);
    }

    /** The image models the tenant may use - to choose the chat's from. */
    getImageModels(scope: TenantScope): Observable<ModelOption[]> {
        return this.http.get<ModelOption[]>(`${this.url(scope)}/image-models`);
    }

    getTemplates(scope: TenantScope): Observable<ChatTemplate[]> {
        return this.http.get<ChatTemplate[]>(`${this.url(scope)}/templates`);
    }

    createTemplate(scope: TenantScope, template: ChatTemplateRequest): Observable<ChatTemplate> {
        return this.http.post<ChatTemplate>(`${this.url(scope)}/templates`, template);
    }

    updateTemplate(scope: TenantScope, id: number, template: ChatTemplateRequest): Observable<ChatTemplate> {
        return this.http.put<ChatTemplate>(`${this.url(scope)}/templates/${id}`, template);
    }

    deleteTemplate(scope: TenantScope, id: number): Observable<void> {
        return this.http.delete<void>(`${this.url(scope)}/templates/${id}`);
    }

    private url(scope: TenantScope): string {
        return `${tenantSettingsUrl(environment.apiUrl, scope)}/assistant`;
    }
}
