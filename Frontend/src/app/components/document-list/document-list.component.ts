import {Component, computed, DestroyRef, effect, inject} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {FormControl, ReactiveFormsModule} from "@angular/forms";
import {Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {MatIconModule} from "@angular/material/icon";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatSelectModule} from "@angular/material/select";
import {MatDialog} from "@angular/material/dialog";
import {MatSnackBar} from "@angular/material/snack-bar";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {PROVIDER_LABELS} from "../../models/ai.model";
import * as DocumentActions from "../../store/document/document.actions";
import * as TenantAiActions from "../../store/tenant-ai/tenant-ai.actions";
import * as TenantAiSelectors from "../../store/tenant-ai/tenant-ai.selectors";
import {ConfirmDialogComponent, ConfirmDialogData} from "../shared/confirm-dialog/confirm-dialog.component";
import {DocumentPanelComponent} from "../shared/document-panel/document-panel.component";
import {KnowledgeSourcesPanelComponent} from "../shared/knowledge-sources-panel/knowledge-sources-panel.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {RerankerPanelComponent} from "../shared/reranker-panel/reranker-panel.component";
import {SearchLanguagePanelComponent} from "../shared/search-language-panel/search-language-panel.component";
import {SearchLanguage} from "../../models/tenant.model";

/**
 * Tenant admin's page "Firmenwissen": the company's knowledge, the model it is embedded with, the language the
 * full-text search reads it in and the search's reranker. The users find it in the chat but never see its list.
 */
@Component({
    selector: "app-document-list",
    imports: [
        ReactiveFormsModule,
        MatIconModule,
        MatFormFieldModule,
        MatSelectModule,
        DocumentPanelComponent,
        KnowledgeSourcesPanelComponent,
        PageHeaderComponent,
        RerankerPanelComponent,
        SearchLanguagePanelComponent,
        TranslocoPipe,
    ],
    template: `
        <div class="page">
            <app-page-header
                [title]="'documents.page.title' | transloco"
                [subtitle]="'documents.page.subtitle' | transloco"
            />

            <div class="app-panel">
                <div class="app-panel-head">
                    <span class="app-panel-title">{{ "documents.embeddingModel.title" | transloco }}</span>
                    <span class="muted">{{ "documents.embeddingModel.pageHint" | transloco }}</span>
                </div>
                <div class="app-panel-body">
                    <mat-form-field appearance="outline" class="model-select">
                        <mat-label>{{ "documents.embeddingModel.label" | transloco }}</mat-label>
                        <mat-select
                            [formControl]="embeddingModel"
                            (selectionChange)="onEmbeddingModelChosen($event.value)"
                        >
                            @for (model of embeddingModels(); track model.id) {
                                <mat-option [value]="model.id" [disabled]="model.keySource === null">
                                    {{ model.displayName }} ({{ providerLabels[model.provider] | transloco }})
                                    @if (model.keySource === null) {
                                        {{ "documents.embeddingModel.noKey" | transloco }}
                                    }
                                </mat-option>
                            }
                        </mat-select>
                        @if (savingModel()) {
                            <mat-hint>{{ "documents.embeddingModel.saving" | transloco }}</mat-hint>
                        }
                    </mat-form-field>
                    @if (settings() && !hasEmbeddingModel()) {
                        <p class="app-note warn">
                            <mat-icon>info</mat-icon>
                            {{ "documents.embeddingModel.chooseFirst" | transloco }}
                        </p>
                    }
                </div>
            </div>

            <app-search-language-panel
                [language]="settings()?.searchLanguage ?? null"
                [saving]="savingLanguage()"
                (chosen)="onSearchLanguageChosen($event)"
            />

            <app-reranker-panel
                [models]="rerankModels()"
                [chosenId]="settings()?.rerankingModelId ?? null"
                [saving]="savingReranker()"
                (chosen)="onRerankerChosen($event)"
            />

            <app-knowledge-sources-panel scope="self" />

            <app-document-panel scope="self" [canUpload]="hasEmbeddingModel()" />
        </div>
    `,
    styles: [
        `
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
export class DocumentListComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);

    readonly embeddingModels = this.store.selectSignal(TenantAiSelectors.selectEmbeddingModels);
    readonly settings = this.store.selectSignal(TenantAiSelectors.selectAiSettings);
    readonly savingModel = this.store.selectSignal(TenantAiSelectors.selectEmbeddingModelSaving);
    readonly savingLanguage = this.store.selectSignal(TenantAiSelectors.selectSearchLanguageSaving);
    readonly rerankModels = this.store.selectSignal(TenantAiSelectors.selectRerankModels);
    readonly savingReranker = this.store.selectSignal(TenantAiSelectors.selectRerankerSaving);
    readonly hasEmbeddingModel = computed(() => this.settings()?.embeddingModelId != null);

    readonly embeddingModel = new FormControl<number | null>(null);
    readonly providerLabels = PROVIDER_LABELS;

    constructor() {
        this.store.dispatch(TenantAiActions.loadModels());
        this.store.dispatch(TenantAiActions.loadSettings());

        // The select shows the stored model - also after a failed or cancelled change.
        effect(() => this.embeddingModel.setValue(this.settings()?.embeddingModelId ?? null));

        this.actions$.pipe(ofType(TenantAiActions.setEmbeddingModelSuccess), takeUntilDestroyed()).subscribe(() => {
            this.snackBar.open(
                this.transloco.translate("documents.embeddingModel.saved"),
                this.transloco.translate("common.ok"),
                {duration: 3000},
            );
            // Re-embedding everything starts now: the list shows it, and the polling follows it.
            this.store.dispatch(DocumentActions.pollDocuments({scope: "self"}));
        });
        this.actions$
            .pipe(ofType(TenantAiActions.setSearchLanguageSuccess), takeUntilDestroyed())
            .subscribe(() =>
                this.snackBar.open(
                    this.transloco.translate("documents.searchLanguage.saved"),
                    this.transloco.translate("common.ok"),
                    {duration: 5000},
                ),
            );
        this.actions$
            .pipe(ofType(TenantAiActions.setSearchLanguageFailure), takeUntilDestroyed())
            .subscribe(({error}) =>
                this.snackBar.open(
                    this.transloco.translate("documents.searchLanguage.saveFailed", {error}),
                    this.transloco.translate("common.ok"),
                    {duration: 10000, panelClass: "error-snackbar"},
                ),
            );
        this.actions$
            .pipe(ofType(TenantAiActions.setRerankingModelSuccess), takeUntilDestroyed())
            .subscribe(() =>
                this.snackBar.open(
                    this.transloco.translate("documents.reranker.saved"),
                    this.transloco.translate("common.ok"),
                    {duration: 3000},
                ),
            );
        this.actions$
            .pipe(ofType(TenantAiActions.setRerankingModelFailure), takeUntilDestroyed())
            .subscribe(({error}) =>
                this.snackBar.open(
                    this.transloco.translate("documents.reranker.saveFailed", {error}),
                    this.transloco.translate("common.ok"),
                    {duration: 10000, panelClass: "error-snackbar"},
                ),
            );
        this.actions$
            .pipe(ofType(TenantAiActions.setEmbeddingModelFailure), takeUntilDestroyed())
            .subscribe(({error}) => {
                this.embeddingModel.setValue(this.settings()?.embeddingModelId ?? null);
                this.snackBar.open(
                    this.transloco.translate("documents.embeddingModel.saveFailed", {error}),
                    this.transloco.translate("common.ok"),
                    {duration: 10000, panelClass: "error-snackbar"},
                );
            });
    }

    onSearchLanguageChosen(language: SearchLanguage): void {
        if (language !== this.settings()?.searchLanguage) {
            this.store.dispatch(TenantAiActions.setSearchLanguage({language}));
        }
    }

    onRerankerChosen(modelId: number | null): void {
        if (modelId !== (this.settings()?.rerankingModelId ?? null)) {
            this.store.dispatch(TenantAiActions.setRerankingModel({modelId}));
        }
    }

    onEmbeddingModelChosen(modelId: number): void {
        const current = this.settings()?.embeddingModelId ?? null;
        if (modelId === current) {
            return;
        }
        if (current === null) {
            this.store.dispatch(TenantAiActions.setEmbeddingModel({modelId}));
            return;
        }
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("documents.embeddingModel.changeMessage"),
                    confirmLabel: this.transloco.translate("documents.embeddingModel.change"),
                },
            })
            .afterClosed()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(confirmed => {
                if (confirmed) {
                    this.store.dispatch(TenantAiActions.setEmbeddingModel({modelId}));
                } else {
                    this.embeddingModel.setValue(current);
                }
            });
    }
}
