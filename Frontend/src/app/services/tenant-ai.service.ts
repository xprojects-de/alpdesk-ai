import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {AiSettings, TenantModel} from "../models/ai.model";
import {SearchLanguage} from "../models/tenant.model";
import {environment} from "../../environments/environment";

/** The models a tenant user can choose from and the tenant's AI settings (changed by a tenant admin). */
@Injectable({
    providedIn: "root",
})
export class TenantAiService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/tenant/ai`;

    getModels(): Observable<TenantModel[]> {
        return this.http.get<TenantModel[]>(`${this.apiUrl}/models`);
    }

    getSettings(): Observable<AiSettings> {
        return this.http.get<AiSettings>(`${this.apiUrl}/settings`);
    }

    /** Embeds every document again with the new model. */
    setEmbeddingModel(modelId: number): Observable<AiSettings> {
        return this.http.put<AiSettings>(`${environment.apiUrl}/tenant/admin/embedding-model`, {modelId});
    }

    /** Switches the knowledge search's reranker on (a model's id) or off (null) - embeds nothing. */
    setRerankingModel(modelId: number | null): Observable<AiSettings> {
        return this.http.put<AiSettings>(`${environment.apiUrl}/tenant/admin/reranking-model`, {modelId});
    }

    /** Builds the full-text search again for the language - embeds nothing. */
    setSearchLanguage(language: SearchLanguage): Observable<AiSettings> {
        return this.http.put<AiSettings>(`${environment.apiUrl}/tenant/admin/search-language`, {language});
    }
}
