import {Component, computed, DestroyRef, inject, input, OnInit} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {NgTemplateOutlet} from "@angular/common";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDatePipe} from "@jsverse/transloco-locale";
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
import {AiProvider, KEY_PROVIDERS, PROVIDER_LABELS, ProviderKey} from "../../models/ai.model";
import {ProviderKeyScope} from "../../services/provider-key.service";
import * as ProviderKeyActions from "../../store/provider-key/provider-key.actions";
import * as ProviderKeySelectors from "../../store/provider-key/provider-key.selectors";
import * as TenantAiActions from "../../store/tenant-ai/tenant-ai.actions";
import * as TenantAiSelectors from "../../store/tenant-ai/tenant-ai.selectors";
import {ConfirmDialogComponent, ConfirmDialogData} from "../shared/confirm-dialog/confirm-dialog.component";
import {DialogSave} from "../shared/dialog-save";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {ProviderKeyDialogComponent, ProviderKeyDialogData} from "./provider-key-dialog.component";

interface ProviderRow {
    provider: AiProvider;
    key: ProviderKey | null;
}

/**
 * The provider keys, one row per provider: the admin's global keys (scope "global"), or a tenant's own keys - kept by
 * its tenant admin (scope "self", which also says what happens without one) or by the admin (a tenant's id, in the
 * tenant list). The scope comes from the route data or, in a dialog, as input.
 */
@Component({
    selector: "app-provider-key-list",
    imports: [
        TranslocoPipe,
        TranslocoDatePipe,
        NgTemplateOutlet,
        MatTableModule,
        MatButtonModule,
        MatIconModule,
        MatProgressBarModule,
        MatTooltipModule,
        PageHeaderComponent,
    ],
    template: `
        <!-- The admin's own page shows a page header; in the tenant admin's page "API-Keys" and in the admin's dialog
             the list is one panel among others and brings its own head. -->
        @if (scope() === "global") {
            <div class="page">
                <app-page-header
                    [title]="'providerKeys.global.title' | transloco"
                    [subtitle]="'providerKeys.global.subtitle' | transloco"
                />
                <ng-container *ngTemplateOutlet="panel" />
            </div>
        } @else {
            <ng-container *ngTemplateOutlet="panel" />
        }

        <ng-template #panel>
            <div class="app-panel">
                <div class="app-panel-head">
                    @if (scope() === "global") {
                        <span class="app-panel-title">{{ "providerKeys.global.panelTitle" | transloco }}</span>
                    } @else {
                        <div class="head-titles">
                            <span class="app-panel-title">{{ "providerKeys.tenant.title" | transloco }}</span>
                            <span class="muted">{{ "providerKeys.tenant.subtitle" | transloco }}</span>
                        </div>
                    }
                </div>

                @if (loading()) {
                    <mat-progress-bar mode="indeterminate" />
                }

                <div class="table-container">
                    <table mat-table [dataSource]="rows()" class="key-table">
                        <ng-container matColumnDef="provider">
                            <th mat-header-cell *matHeaderCellDef>{{ "providerKeys.column.provider" | transloco }}</th>
                            <td mat-cell *matCellDef="let row">
                                <div class="cell-title">{{ providerLabels[row.provider] | transloco }}</div>
                                @if (scope() === "self" && !row.key && fallbackHint(); as hint) {
                                    <div class="fallback">{{ hint | transloco }}</div>
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="key">
                            <th mat-header-cell *matHeaderCellDef>{{ "providerKeys.column.key" | transloco }}</th>
                            <td mat-cell *matCellDef="let row">
                                @if (row.key) {
                                    <span class="mono">{{ row.key.keyHint }}</span>
                                } @else {
                                    <span class="app-chip">{{ "providerKeys.notSet" | transloco }}</span>
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="updatedAt">
                            <th mat-header-cell *matHeaderCellDef>{{ "providerKeys.column.updated" | transloco }}</th>
                            <td mat-cell *matCellDef="let row" class="num muted">
                                {{
                                    row.key
                                        ? (row.key.updatedAt | translocoDate: {dateStyle: "medium", timeStyle: "short"})
                                        : "–"
                                }}
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="actions">
                            <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                            <td mat-cell *matCellDef="let row" class="actions-column">
                                <button mat-stroked-button (click)="openKeyDialog(row)">
                                    <mat-icon>key</mat-icon>
                                    {{ (row.key ? "providerKeys.replaceKey" : "providerKeys.setKey") | transloco }}
                                </button>
                                @if (row.key) {
                                    <button
                                        mat-icon-button
                                        color="warn"
                                        (click)="confirmRemove(row.provider)"
                                        [matTooltip]="'providerKeys.remove' | transloco"
                                    >
                                        <mat-icon>delete</mat-icon>
                                    </button>
                                }
                            </td>
                        </ng-container>
                        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                        <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
                    </table>
                </div>
            </div>
        </ng-template>
    `,
    styles: [
        `
            .key-table {
                width: 100%;
            }

            .head-titles {
                display: flex;
                flex-direction: column;
                gap: 2px;
            }

            .head-titles .muted {
                font-size: 13px;
            }

            .cell-title {
                font-weight: 500;
            }

            .fallback {
                font-size: 12px;
                margin-top: 2px;
                color: var(--app-text-3);
            }

            .actions-column button {
                margin-left: 8px;
                vertical-align: middle;
            }
        `,
    ],
})
export class ProviderKeyListComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);
    private destroyRef = inject(DestroyRef);

    /** Bound from the route data (router input binding) or set by a dialog. */
    readonly scope = input.required<ProviderKeyScope>();
    // Keyed by string: table rows (matCellDef) are untyped in the template.
    readonly providerLabels: Record<string, string> = PROVIDER_LABELS;
    readonly displayedColumns = ["provider", "key", "updatedAt", "actions"];

    private readonly keys = this.store.selectSignal(ProviderKeySelectors.selectProviderKeys);
    readonly loading = this.store.selectSignal(ProviderKeySelectors.selectProviderKeysLoading);
    private readonly settings = this.store.selectSignal(TenantAiSelectors.selectAiSettings);

    /** Every provider with keys, also those without one. */
    readonly rows = computed<ProviderRow[]>(() =>
        KEY_PROVIDERS.map(provider => ({provider, key: this.keys().find(key => key.provider === provider) ?? null})),
    );

    /** Tenant area: the key of what a provider without an own key falls back to; null until the settings are loaded. */
    readonly fallbackHint = computed(() => {
        const settings = this.settings();
        if (!settings) {
            return null;
        }
        return settings.globalKeysAllowed ? "providerKeys.fallback.global" : "providerKeys.fallback.none";
    });

    constructor() {
        this.notifyOn(ProviderKeyActions.setKeySuccess, () => this.translate("providerKeys.snack.saved"));
        this.notifyOn(ProviderKeyActions.removeKeySuccess, () => this.translate("providerKeys.snack.removed"));
        this.notifyOn(
            ProviderKeyActions.setKeyFailure,
            ({error}) => this.translate("providerKeys.snack.saveError", {error}),
            true,
        );
        this.notifyOn(
            ProviderKeyActions.removeKeyFailure,
            ({error}) => this.translate("providerKeys.snack.removeError", {error}),
            true,
        );
    }

    ngOnInit(): void {
        this.store.dispatch(ProviderKeyActions.loadKeys({scope: this.scope()}));
        if (this.scope() === "self") {
            this.store.dispatch(TenantAiActions.loadSettings());
        }
    }

    openKeyDialog(row: ProviderRow): void {
        this.dialogSave.open<ProviderKeyDialogComponent, ProviderKeyDialogData, string>(
            ProviderKeyDialogComponent,
            {width: "520px", data: {provider: row.provider, replacing: row.key !== null}},
            {
                action: apiKey => ProviderKeyActions.setKey({scope: this.scope(), provider: row.provider, apiKey}),
                success: [ProviderKeyActions.setKeySuccess],
                failure: ProviderKeyActions.setKeyFailure,
            },
        );
    }

    confirmRemove(provider: AiProvider): void {
        const consequence = this.translate(
            this.scope() === "global" ? "providerKeys.confirmRemove.global" : "providerKeys.confirmRemove.tenant",
        );
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.translate("providerKeys.confirmRemove.message", {
                        provider: this.translate(PROVIDER_LABELS[provider]),
                        consequence,
                    }),
                    confirmLabel: this.translate("providerKeys.remove"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(ProviderKeyActions.removeKey({scope: this.scope(), provider})));
    }

    /** Must be called from the constructor (injection context) - takeUntilDestroyed() relies on it. */
    private notifyOn<AC extends ActionCreator<string, Creator>>(
        action: AC,
        message: (payload: ReturnType<AC>) => string,
        isError = false,
    ): void {
        this.actions$.pipe(ofType(action), takeUntilDestroyed()).subscribe(payload =>
            this.snackBar.open(message(payload), this.translate("common.ok"), {
                duration: isError ? 10000 : 3000,
                panelClass: isError ? "error-snackbar" : undefined,
            }),
        );
    }

    private translate(key: string, params?: Record<string, unknown>): string {
        return this.transloco.translate(key, params);
    }
}
