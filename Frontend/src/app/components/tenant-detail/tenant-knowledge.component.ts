import {Component, computed, DestroyRef, effect, inject, input, signal} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {FormControl, ReactiveFormsModule} from "@angular/forms";
import {Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {MatDialog} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatIconModule} from "@angular/material/icon";
import {MatSelectModule} from "@angular/material/select";
import {MatSnackBar} from "@angular/material/snack-bar";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {PROVIDER_LABELS} from "../../models/ai.model";
import {SearchLanguage, Tenant} from "../../models/tenant.model";
import * as AiModelActions from "../../store/ai-model/ai-model.actions";
import * as AiModelSelectors from "../../store/ai-model/ai-model.selectors";
import * as DocumentActions from "../../store/document/document.actions";
import * as TenantActions from "../../store/tenant/tenant.actions";
import * as TenantModelActions from "../../store/tenant-model/tenant-model.actions";
import * as TenantModelSelectors from "../../store/tenant-model/tenant-model.selectors";
import {embeddingModelChoices, rerankerChoices} from "../../utils/tenant-model.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../shared/confirm-dialog/confirm-dialog.component";
import {DocumentPanelComponent} from "../shared/document-panel/document-panel.component";
import {KnowledgeSourcesPanelComponent} from "../shared/knowledge-sources-panel/knowledge-sources-panel.component";
import {RerankerPanelComponent} from "../shared/reranker-panel/reranker-panel.component";
import {SearchLanguagePanelComponent} from "../shared/search-language-panel/search-language-panel.component";

/**
 * The admin keeps one tenant's company knowledge and its areas (tab of the tenant page): the documents its users and
 * its client software find in the chat without seeing them - an area's only those assigned to it -, the embedding
 * model they are embedded with, the language the full-text search reads them in and the search's reranker.
 */
@Component({
    selector: "app-tenant-knowledge",
    imports: [
        ReactiveFormsModule,
        MatFormFieldModule,
        MatIconModule,
        MatSelectModule,
        DocumentPanelComponent,
        KnowledgeSourcesPanelComponent,
        RerankerPanelComponent,
        SearchLanguagePanelComponent,
        TranslocoPipe,
    ],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "documents.embeddingModel.title" | transloco }}</span>
                <span class="muted">{{ "documents.embeddingModel.tenantHint" | transloco }}</span>
            </div>
            <div class="app-panel-body">
                <mat-form-field appearance="outline" class="model-select">
                    <mat-label>{{ "documents.embeddingModel.label" | transloco }}</mat-label>
                    <mat-select [formControl]="embeddingModel" (selectionChange)="onEmbeddingModelChosen($event.value)">
                        @for (model of embeddingModels(); track model.id) {
                            <mat-option [value]="model.id">
                                {{ model.displayName }} ({{ providerLabels[model.provider] | transloco }})
                            </mat-option>
                        }
                    </mat-select>
                </mat-form-field>
                @if (!hasEmbeddingModel()) {
                    <p class="app-note warn">
                        <mat-icon>info</mat-icon>
                        @if (embeddingModels().length === 0) {
                            {{ "documents.embeddingModel.noneAssigned" | transloco }}
                        } @else {
                            {{ "documents.embeddingModel.chooseFirst" | transloco }}
                        }
                    </p>
                }
            </div>
        </div>

        <app-search-language-panel
            [language]="tenant().searchLanguage"
            [saving]="savingLanguage()"
            (chosen)="onSearchLanguageChosen($event)"
        />

        <app-reranker-panel
            [models]="rerankModels()"
            [chosenId]="tenant().rerankingModelId"
            [saving]="savingReranker()"
            (chosen)="onRerankerChosen($event)"
        />

        <app-knowledge-sources-panel [scope]="tenant().id" />

        <app-document-panel [scope]="tenant().id" [canUpload]="hasEmbeddingModel()" />
    `,
    styles: [
        `
            :host {
                display: flex;
                flex-direction: column;
                gap: 16px;
            }

            .model-select {
                width: min(480px, 100%);
                margin-bottom: 0 !important;
            }

            .app-note {
                margin: 12px 0 0;
            }
        `,
    ],
})
export class TenantKnowledgeComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);
    /** The tenant as the store has it now - choosing the embedding model changes it. */
    readonly tenant = input.required<Tenant>();
    private readonly catalog = this.store.selectSignal(AiModelSelectors.selectAiModels);
    private readonly tenantModels = this.store.selectSignal(TenantModelSelectors.selectTenantModelState);
    /** The models assigned to this tenant; null while they are not loaded (the slice holds one tenant at a time). */
    private readonly assigned = computed(() => {
        const state = this.tenantModels();
        return state.tenantId === this.tenant().id ? state.modelIds : null;
    });

    readonly embeddingModels = computed(() =>
        embeddingModelChoices(this.catalog(), this.assigned() ?? [], this.tenant().embeddingModelId),
    );
    readonly rerankModels = computed(() =>
        rerankerChoices(this.catalog(), this.assigned() ?? [], this.tenant().rerankingModelId),
    );
    readonly hasEmbeddingModel = computed(() => this.tenant().embeddingModelId !== null);
    readonly embeddingModel = new FormControl<number | null>(null);
    readonly providerLabels = PROVIDER_LABELS;
    /** A change of the search language is being saved - the tenant slice keeps no flag for it. */
    readonly savingLanguage = signal(false);
    /** A change of the reranker is being saved. */
    readonly savingReranker = signal(false);

    constructor() {
        this.store.dispatch(AiModelActions.loadModels());
        effect(() => this.store.dispatch(TenantModelActions.loadTenantModels({tenantId: this.tenant().id})));

        // The select shows the stored model - also after a failed or cancelled change.
        effect(() => this.embeddingModel.setValue(this.tenant().embeddingModelId));

        this.actions$.pipe(ofType(TenantActions.setEmbeddingModelSuccess), takeUntilDestroyed()).subscribe(() => {
            this.snackBar.open(
                this.transloco.translate("documents.embeddingModel.saved"),
                this.transloco.translate("common.ok"),
                {duration: 3000},
            );
            // Re-embedding everything starts now: the list shows it, and the polling follows it.
            this.store.dispatch(DocumentActions.pollDocuments({scope: this.tenant().id}));
        });
        this.actions$.pipe(ofType(TenantActions.setSearchLanguageSuccess), takeUntilDestroyed()).subscribe(() => {
            this.savingLanguage.set(false);
            this.snackBar.open(
                this.transloco.translate("documents.searchLanguage.saved"),
                this.transloco.translate("common.ok"),
                {duration: 5000},
            );
        });
        this.actions$
            .pipe(ofType(TenantActions.setSearchLanguageFailure), takeUntilDestroyed())
            .subscribe(({error}) => {
                this.savingLanguage.set(false);
                this.snackBar.open(
                    this.transloco.translate("documents.searchLanguage.saveFailed", {error}),
                    this.transloco.translate("common.ok"),
                    {duration: 10000, panelClass: "error-snackbar"},
                );
            });
        this.actions$.pipe(ofType(TenantActions.setRerankingModelSuccess), takeUntilDestroyed()).subscribe(() => {
            this.savingReranker.set(false);
            this.snackBar.open(
                this.transloco.translate("documents.reranker.saved"),
                this.transloco.translate("common.ok"),
                {duration: 3000},
            );
        });
        this.actions$
            .pipe(ofType(TenantActions.setRerankingModelFailure), takeUntilDestroyed())
            .subscribe(({error}) => {
                this.savingReranker.set(false);
                this.snackBar.open(
                    this.transloco.translate("documents.reranker.saveFailed", {error}),
                    this.transloco.translate("common.ok"),
                    {duration: 10000, panelClass: "error-snackbar"},
                );
            });
        this.actions$
            .pipe(ofType(TenantActions.setEmbeddingModelFailure), takeUntilDestroyed())
            .subscribe(({error}) => {
                this.embeddingModel.setValue(this.tenant().embeddingModelId);
                this.snackBar.open(
                    this.transloco.translate("documents.embeddingModel.saveFailed", {error}),
                    this.transloco.translate("common.ok"),
                    {duration: 10000, panelClass: "error-snackbar"},
                );
            });
    }

    onSearchLanguageChosen(language: SearchLanguage): void {
        if (language === this.tenant().searchLanguage) {
            return;
        }
        this.savingLanguage.set(true);
        this.store.dispatch(TenantActions.setSearchLanguage({tenantId: this.tenant().id, language}));
    }

    onRerankerChosen(modelId: number | null): void {
        if (modelId === this.tenant().rerankingModelId) {
            return;
        }
        this.savingReranker.set(true);
        this.store.dispatch(TenantActions.setRerankingModel({tenantId: this.tenant().id, modelId}));
    }

    onEmbeddingModelChosen(modelId: number): void {
        const current = this.tenant().embeddingModelId;
        if (modelId === current) {
            return;
        }
        if (current === null) {
            this.save(modelId);
            return;
        }
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("documents.embeddingModel.changeMessageTenant"),
                    confirmLabel: this.transloco.translate("documents.embeddingModel.change"),
                },
            })
            .afterClosed()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(confirmed => {
                if (confirmed) {
                    this.save(modelId);
                } else {
                    this.embeddingModel.setValue(current);
                }
            });
    }

    private save(modelId: number): void {
        this.store.dispatch(TenantActions.setEmbeddingModel({tenantId: this.tenant().id, modelId}));
    }
}
