import {Component, computed, effect, inject, input, output} from "@angular/core";
import {Store} from "@ngrx/store";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {KIND_LABELS, ModelKind, PROVIDER_LABELS} from "../../models/ai.model";
import {Tenant} from "../../models/tenant.model";
import * as AiModelActions from "../../store/ai-model/ai-model.actions";
import * as AiModelSelectors from "../../store/ai-model/ai-model.selectors";
import * as TenantModelActions from "../../store/tenant-model/tenant-model.actions";
import * as TenantModelSelectors from "../../store/tenant-model/tenant-model.selectors";
import {EmptyStateComponent} from "../shared/empty-state/empty-state.component";
import {TranslocoPipe} from "@jsverse/transloco";

/** The catalog models a tenant may use, as a list - assigned in the model dialog the edit button opens. */
@Component({
    selector: "app-tenant-models-summary",
    imports: [MatButtonModule, MatIconModule, MatProgressBarModule, EmptyStateComponent, TranslocoPipe],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "tenantDetail.models.title" | transloco }}</span>
                <span class="spacer"></span>
                <button mat-stroked-button (click)="edit.emit()">
                    <mat-icon>tune</mat-icon>
                    {{ (compact() ? "tenantDetail.models.manage" : "tenantDetail.models.assign") | transloco }}
                </button>
            </div>
            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }
            @if (assigned(); as models) {
                @if (models.length > 0) {
                    <div class="rows">
                        @for (model of models; track model.id) {
                            <div class="row">
                                <mat-icon>{{ kindIcons[model.kind] }}</mat-icon>
                                <div class="grow">
                                    <div>{{ model.displayName }}</div>
                                    <div class="cell-sub">
                                        {{ kindLabels[model.kind] | transloco }} ·
                                        {{ providerLabels[model.provider] | transloco }}
                                        @if (model.id === tenant().embeddingModelId) {
                                            · {{ "tenantDetail.models.embedsDocuments" | transloco }}
                                        }
                                    </div>
                                </div>
                                <span class="app-chip" [class.ok]="model.active">
                                    {{ (model.active ? "common.active" : "common.inactive") | transloco }}
                                </span>
                            </div>
                        }
                    </div>
                } @else {
                    <app-empty-state
                        icon="smart_toy"
                        [title]="'tenantDetail.models.emptyTitle' | transloco"
                        [text]="'tenantDetail.models.emptyText' | transloco"
                    />
                }
            }
        </div>
    `,
    styles: [
        `
            .rows > .row {
                display: flex;
                align-items: center;
                gap: 12px;
                padding: 11px 14px;
                border-bottom: 1px solid var(--app-border);
            }

            .rows > .row:last-child {
                border-bottom: 0;
            }

            .row mat-icon {
                color: var(--app-text-3);
            }

            .grow {
                flex: 1 1 auto;
                min-width: 0;
            }

            .cell-sub {
                font-size: 12px;
                color: var(--app-text-3);
            }
        `,
    ],
})
export class TenantModelsSummaryComponent {
    private store = inject(Store);

    readonly tenant = input.required<Tenant>();
    /** On the overview: a smaller button, the list is only a glance. */
    readonly compact = input(false);
    readonly edit = output<void>();

    readonly kindLabels = KIND_LABELS;
    readonly kindIcons: Record<ModelKind, string> = {
        CHAT: "chat",
        EMBEDDING: "data_array",
        IMAGE: "image",
        WEB_SEARCH: "travel_explore",
        RERANK: "sort",
    };
    readonly providerLabels = PROVIDER_LABELS;
    private readonly catalog = this.store.selectSignal(AiModelSelectors.selectAiModels);
    private readonly tenantModels = this.store.selectSignal(TenantModelSelectors.selectTenantModelState);
    readonly loading = computed(() => this.tenantModels().loading);
    /** The assigned models in the catalog's order; null while they are not loaded (one tenant at a time in the slice). */
    readonly assigned = computed(() => {
        const state = this.tenantModels();
        if (state.tenantId !== this.tenant().id || state.modelIds === null) {
            return null;
        }
        const ids = state.modelIds;
        return this.catalog().filter(model => ids.includes(model.id));
    });

    constructor() {
        this.store.dispatch(AiModelActions.loadModels());
        effect(() => this.store.dispatch(TenantModelActions.loadTenantModels({tenantId: this.tenant().id})));
    }
}
