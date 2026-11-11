import {Component, computed, inject, linkedSignal} from "@angular/core";
import {Store} from "@ngrx/store";
import {MatDialogRef, MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatCheckboxModule} from "@angular/material/checkbox";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {AiModel, PROVIDER_LABELS} from "../../models/ai.model";
import {Tenant} from "../../models/tenant.model";
import * as AiModelActions from "../../store/ai-model/ai-model.actions";
import * as AiModelSelectors from "../../store/ai-model/ai-model.selectors";
import * as TenantModelActions from "../../store/tenant-model/tenant-model.actions";
import * as TenantModelSelectors from "../../store/tenant-model/tenant-model.selectors";
import {buildTenantModelIds, tenantModelGroups} from "../../utils/tenant-model.util";
import {dialogSave} from "../shared/dialog-save";
import {TranslocoPipe} from "@jsverse/transloco";

/**
 * The catalog models one tenant may use, as check boxes. Closes with the ids to assign; the model the tenant's
 * documents are embedded with cannot be unchecked (the backend refuses that with 409).
 */
@Component({
    selector: "app-tenant-models-dialog",
    imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, MatProgressBarModule, TranslocoPipe],
    template: `
        <h2 mat-dialog-title>{{ "tenants.modelsDialog.title" | transloco: {name: tenant.name} }}</h2>
        <mat-dialog-content>
            <p class="hint">{{ "tenants.modelsDialog.intro" | transloco }}</p>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (checked(); as checked) {
                @for (group of groups(); track group.kind) {
                    <h3>{{ group.label | transloco }}</h3>
                    @for (model of group.models; track model.id) {
                        <div class="model">
                            <mat-checkbox
                                [checked]="checked.has(model.id) || isEmbeddingModel(model)"
                                [disabled]="isEmbeddingModel(model) || saver.saving()"
                                (change)="toggle(model.id, $event.checked)"
                            >
                                {{ model.displayName }}
                                <span class="meta">
                                    {{ providerLabels[model.provider] | transloco
                                    }}{{ model.active ? "" : ("tenants.modelsDialog.inactiveSuffix" | transloco) }}
                                </span>
                            </mat-checkbox>
                            @if (isEmbeddingModel(model)) {
                                <div class="locked">
                                    {{ "tenants.modelsDialog.embeddingLocked" | transloco }}
                                </div>
                            }
                        </div>
                    }
                } @empty {
                    @if (!loading()) {
                        <p class="hint">{{ "tenants.modelsDialog.empty" | transloco }}</p>
                    }
                }
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button [disabled]="!checked() || saver.saving()" (click)="onSave()">
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .hint {
                color: var(--app-text-2);
                margin-top: 8px;
            }

            h3 {
                margin: 16px 0 4px;
                font-size: 14px;
                font-weight: 500;
            }

            .model {
                min-width: min(420px, 70vw);
            }

            .meta {
                margin-left: 6px;
                font-size: 12px;
                color: var(--app-text-2);
            }

            .locked {
                margin: -4px 0 4px 40px;
                font-size: 12px;
                color: var(--app-text-2);
            }
        `,
    ],
})
export class TenantModelsDialogComponent {
    private store = inject(Store);
    private dialogRef = inject<MatDialogRef<TenantModelsDialogComponent, number[]>>(MatDialogRef);
    /** Saves and closes only when saved - on an error the dialog stays open with the selection. */
    readonly saver = dialogSave<number[]>();
    readonly tenant = inject<Tenant>(MAT_DIALOG_DATA);
    readonly providerLabels = PROVIDER_LABELS;

    private readonly catalog = this.store.selectSignal(AiModelSelectors.selectAiModels);
    private readonly assigned = this.store.selectSignal(TenantModelSelectors.selectAssignedModelIds(this.tenant.id));
    private readonly catalogLoading = this.store.selectSignal(AiModelSelectors.selectAiModelsLoading);
    private readonly assignedLoading = this.store.selectSignal(TenantModelSelectors.selectTenantModelsLoading);

    readonly loading = computed(() => this.catalogLoading() || this.assignedLoading());
    readonly groups = computed(() => tenantModelGroups(this.catalog()));
    /** The check boxes' state, starting from the loaded assignment; null until it is loaded. */
    readonly checked = linkedSignal(() => {
        const ids = this.assigned();
        return ids ? new Set(ids) : null;
    });

    constructor() {
        this.store.dispatch(AiModelActions.loadModels());
        this.store.dispatch(TenantModelActions.loadTenantModels({tenantId: this.tenant.id}));
    }

    isEmbeddingModel(model: AiModel): boolean {
        return model.id === this.tenant.embeddingModelId;
    }

    toggle(modelId: number, checked: boolean): void {
        this.checked.update(current => {
            const next = new Set(current);
            if (checked) {
                next.add(modelId);
            } else {
                next.delete(modelId);
            }
            return next;
        });
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        const checked = this.checked();
        if (!checked) {
            return;
        }
        this.saver.submit(buildTenantModelIds(this.catalog(), checked, this.tenant.embeddingModelId));
    }
}
