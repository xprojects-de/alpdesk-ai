import {Injectable, inject} from "@angular/core";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {AiModel, AiModelRequest} from "../models/ai.model";
import {environment} from "../../environments/environment";

/** The admin's model catalog. */
@Injectable({
    providedIn: "root",
})
export class AiModelService {
    private http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/admin/models`;

    getAll(): Observable<AiModel[]> {
        return this.http.get<AiModel[]>(this.apiUrl);
    }

    create(model: AiModelRequest): Observable<AiModel> {
        return this.http.post<AiModel>(this.apiUrl, model);
    }

    update(id: number, model: AiModelRequest): Observable<AiModel> {
        return this.http.put<AiModel>(`${this.apiUrl}/${id}`, model);
    }

    delete(id: number): Observable<void> {
        return this.http.delete<void>(`${this.apiUrl}/${id}`);
    }
}
