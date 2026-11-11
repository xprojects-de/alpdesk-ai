import {Component, DestroyRef, inject} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {ActionCreator, Creator, Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {filter} from "rxjs/operators";
import {MatTableModule} from "@angular/material/table";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatDialog} from "@angular/material/dialog";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTooltipModule} from "@angular/material/tooltip";
import {AiModel, AiModelRequest, KIND_LABELS, PROVIDER_LABELS} from "../../models/ai.model";
import * as AiModelActions from "../../store/ai-model/ai-model.actions";
import * as AiModelSelectors from "../../store/ai-model/ai-model.selectors";
import {ConfirmDialogComponent, ConfirmDialogData} from "../shared/confirm-dialog/confirm-dialog.component";
import {DialogSave, DialogSaveSpec} from "../shared/dialog-save";
import {EmptyStateComponent} from "../shared/empty-state/empty-state.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {AiModelDialogComponent} from "./ai-model-dialog.component";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDecimalPipe} from "@jsverse/transloco-locale";

/** Admin page: the catalog of AI models the tenants can choose from, with their prices. */
@Component({
    selector: "app-ai-model-list",
    imports: [
        TranslocoPipe,
        TranslocoDecimalPipe,
        MatTableModule,
        MatButtonModule,
        MatIconModule,
        MatProgressBarModule,
        MatTooltipModule,
        PageHeaderComponent,
        EmptyStateComponent,
    ],
    template: `
        <div class="page">
            <app-page-header [title]="'models.list.title' | transloco" [subtitle]="'models.list.subtitle' | transloco">
                <button mat-flat-button (click)="openCreateDialog()">
                    <mat-icon>add</mat-icon>
                    {{ "models.list.create" | transloco }}
                </button>
            </app-page-header>

            <div class="app-panel">
                <div class="app-panel-head">
                    <span class="app-panel-title">{{ "models.list.catalog" | transloco }}</span>
                    <span class="muted">{{ "models.list.pricesNote" | transloco }}</span>
                    <span class="spacer"></span>
                    <button
                        mat-icon-button
                        (click)="refresh()"
                        [matTooltip]="'models.list.refresh' | transloco"
                        [attr.aria-label]="'models.list.refresh' | transloco"
                    >
                        <mat-icon>refresh</mat-icon>
                    </button>
                </div>

                @if (loading()) {
                    <mat-progress-bar mode="indeterminate" />
                }

                @if (models().length === 0 && !loading()) {
                    <app-empty-state
                        icon="smart_toy"
                        [title]="'models.list.emptyTitle' | transloco"
                        [text]="'models.list.emptyText' | transloco"
                    >
                        <button mat-flat-button (click)="openCreateDialog()">
                            <mat-icon>add</mat-icon>
                            {{ "models.list.create" | transloco }}
                        </button>
                    </app-empty-state>
                } @else {
                    <div class="table-container">
                        <table mat-table [dataSource]="models()" [trackBy]="trackById" class="model-table">
                            <ng-container matColumnDef="displayName">
                                <th mat-header-cell *matHeaderCellDef>{{ "common.name" | transloco }}</th>
                                <td mat-cell *matCellDef="let model">
                                    <div class="cell-main">
                                        <div>
                                            <div class="cell-title">{{ model.displayName }}</div>
                                            <div class="cell-sub mono">{{ model.modelName }}</div>
                                        </div>
                                    </div>
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="provider">
                                <th mat-header-cell *matHeaderCellDef>
                                    {{ "models.list.columnProvider" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let model">
                                    {{ providerLabels[model.provider] | transloco }}
                                    @if (model.baseUrl) {
                                        <div class="server">{{ serverHost(model.baseUrl) }}</div>
                                    }
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="kind">
                                <th mat-header-cell *matHeaderCellDef>{{ "models.list.columnKind" | transloco }}</th>
                                <td mat-cell *matCellDef="let model">
                                    <span class="app-tag">{{ kindLabels[model.kind] | transloco }}</span>
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="dimension">
                                <th mat-header-cell *matHeaderCellDef class="right">
                                    {{ "models.list.columnDimension" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let model" class="right num">{{ model.dimension ?? "–" }}</td>
                            </ng-container>
                            <ng-container matColumnDef="capabilities">
                                <th mat-header-cell *matHeaderCellDef>{{ "models.list.columnReads" | transloco }}</th>
                                <td mat-cell *matCellDef="let model" class="capabilities">
                                    @if (model.readsPdf) {
                                        <mat-icon [matTooltip]="'models.list.readsPdf' | transloco"
                                            >picture_as_pdf</mat-icon
                                        >
                                    }
                                    @if (model.readsAudio) {
                                        <mat-icon [matTooltip]="'models.list.readsAudio' | transloco"
                                            >graphic_eq</mat-icon
                                        >
                                    }
                                    @if (model.transcribes) {
                                        <mat-icon
                                            class="transcribes"
                                            [matTooltip]="'models.list.transcribes' | transloco"
                                        >
                                            record_voice_over
                                        </mat-icon>
                                    }
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="prices">
                                <th mat-header-cell *matHeaderCellDef class="right">
                                    {{ "models.list.columnPrices" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let model" class="right num">
                                    @if (model.kind === "WEB_SEARCH") {
                                        {{ model.inputPrice | translocoDecimal: price }}
                                        {{ "models.list.perThousandSearches" | transloco }}
                                    } @else {
                                        {{ model.inputPrice | translocoDecimal: price }} /
                                        {{ model.outputPrice | translocoDecimal: price }}
                                    }
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="active">
                                <th mat-header-cell *matHeaderCellDef>{{ "models.list.columnStatus" | transloco }}</th>
                                <td mat-cell *matCellDef="let model">
                                    <span class="app-chip" [class.ok]="model.active">
                                        {{ (model.active ? "common.active" : "common.inactive") | transloco }}
                                    </span>
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="actions">
                                <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                                <td mat-cell *matCellDef="let model" class="actions-column">
                                    <button
                                        mat-icon-button
                                        (click)="openEditDialog(model)"
                                        [matTooltip]="'common.edit' | transloco"
                                        [attr.aria-label]="'common.edit' | transloco"
                                    >
                                        <mat-icon>edit</mat-icon>
                                    </button>
                                    <button
                                        mat-icon-button
                                        color="warn"
                                        (click)="confirmDelete(model)"
                                        [matTooltip]="'common.delete' | transloco"
                                        [attr.aria-label]="'common.delete' | transloco"
                                    >
                                        <mat-icon>delete</mat-icon>
                                    </button>
                                </td>
                            </ng-container>

                            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                            <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
                        </table>
                    </div>
                }
            </div>
        </div>
    `,
    styles: [
        `
            .model-table {
                width: 100%;
            }

            .right {
                text-align: right;
            }

            .capabilities mat-icon {
                font-size: 18px;
                width: 18px;
                height: 18px;
                margin-right: 4px;
                color: var(--app-text-2);
                vertical-align: middle;
            }

            .capabilities .transcribes {
                color: var(--app-brand-text);
            }

            .server {
                font-size: 12px;
                color: var(--app-text-3);
            }
        `,
    ],
})
export class AiModelListComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);

    readonly models = this.store.selectSignal(AiModelSelectors.selectAiModels);
    readonly loading = this.store.selectSignal(AiModelSelectors.selectAiModelsLoading);
    // Keyed by string: table rows (matCellDef) are untyped in the template.
    readonly providerLabels: Record<string, string> = PROVIDER_LABELS;
    // Keyed by string: table rows (matCellDef) are untyped in the template.
    readonly kindLabels: Record<string, string> = KIND_LABELS;
    /** Prices per million tokens: two to four decimals, as the catalog is entered. */
    readonly price = {minimumFractionDigits: 2, maximumFractionDigits: 4};

    readonly displayedColumns = [
        "displayName",
        "provider",
        "kind",
        "dimension",
        "capabilities",
        "prices",
        "active",
        "actions",
    ];
    readonly trackById = (_index: number, model: AiModel) => model.id;

    constructor() {
        this.store.dispatch(AiModelActions.loadModels());

        this.notifyOn(AiModelActions.createModelSuccess, () => this.transloco.translate("models.snack.created"));
        this.notifyOn(AiModelActions.updateModelSuccess, () => this.transloco.translate("models.snack.updated"));
        this.notifyOn(AiModelActions.deleteModelSuccess, () => this.transloco.translate("models.snack.deleted"));
        this.notifyOn(
            AiModelActions.createModelFailure,
            ({error}) => this.transloco.translate("models.snack.createFailed", {error}),
            true,
        );
        this.notifyOn(
            AiModelActions.updateModelFailure,
            ({error}) => this.transloco.translate("models.snack.updateFailed", {error}),
            true,
        );
        this.notifyOn(
            AiModelActions.deleteModelFailure,
            ({error}) => this.transloco.translate("models.snack.deleteFailed", {error}),
            true,
        );
    }

    /** Only the host - the address's path is in the dialog. */
    serverHost(baseUrl: string): string {
        try {
            return new URL(baseUrl).host;
        } catch {
            return baseUrl;
        }
    }

    refresh(): void {
        this.store.dispatch(AiModelActions.loadModels());
    }

    openCreateDialog(): void {
        this.openDialog(null, {
            action: model => AiModelActions.createModel({model}),
            success: [AiModelActions.createModelSuccess],
            failure: AiModelActions.createModelFailure,
        });
    }

    openEditDialog(existing: AiModel): void {
        this.openDialog(existing, {
            action: model => AiModelActions.updateModel({id: existing.id, model}),
            success: [AiModelActions.updateModelSuccess],
            failure: AiModelActions.updateModelFailure,
        });
    }

    confirmDelete(model: AiModel): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("models.list.confirmDelete", {name: model.displayName}),
                    confirmLabel: this.transloco.translate("common.delete"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(AiModelActions.deleteModel({id: model.id})));
    }

    private openDialog(data: AiModel | null, spec: DialogSaveSpec<AiModelRequest>): void {
        this.dialogSave.open<AiModelDialogComponent, AiModel | null, AiModelRequest>(
            AiModelDialogComponent,
            {width: "620px", maxWidth: "95vw", data},
            spec,
        );
    }

    /** Must be called from the constructor (injection context) - takeUntilDestroyed() relies on it. */
    private notifyOn<AC extends ActionCreator<string, Creator>>(
        action: AC,
        message: (payload: ReturnType<AC>) => string,
        isError = false,
    ): void {
        this.actions$.pipe(ofType(action), takeUntilDestroyed()).subscribe(payload =>
            this.snackBar.open(message(payload), this.transloco.translate("common.ok"), {
                duration: isError ? 10000 : 3000,
                panelClass: isError ? "error-snackbar" : undefined,
            }),
        );
    }
}
