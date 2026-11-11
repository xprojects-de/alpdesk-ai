import {Component, DestroyRef, effect, inject, viewChild} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {ActionCreator, Creator, Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {filter} from "rxjs/operators";
import {MatTableModule, MatTableDataSource} from "@angular/material/table";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatMenuModule} from "@angular/material/menu";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatDialog} from "@angular/material/dialog";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MatSortModule, MatSort} from "@angular/material/sort";
import {MatPaginatorModule, MatPaginator} from "@angular/material/paginator";
import {Tenant, TenantRequest} from "../../models/tenant.model";
import * as TenantActions from "../../store/tenant/tenant.actions";
import * as TenantSelectors from "../../store/tenant/tenant.selectors";
import {TenantDialogComponent} from "./tenant-dialog.component";
import {ConfirmDialogComponent, ConfirmDialogData} from "../shared/confirm-dialog/confirm-dialog.component";
import {DialogSave, DialogSaveSpec} from "../shared/dialog-save";
import * as BrandingSelectors from "../../store/branding/branding.selectors";
import {Router} from "@angular/router";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {EmptyStateComponent} from "../shared/empty-state/empty-state.component";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";

/**
 * Admin page: tenants (Mandanten). A row opens the tenant's page (tenant-detail) with its users, models, company
 * knowledge, keys and connected software.
 */
@Component({
    selector: "app-tenant-list",
    imports: [
        MatTableModule,
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatProgressBarModule,
        MatTooltipModule,
        MatSortModule,
        MatPaginatorModule,
        PageHeaderComponent,
        EmptyStateComponent,
        TranslocoPipe,
    ],
    template: `
        <div class="page">
            <app-page-header
                [title]="'tenants.list.title' | transloco"
                [subtitle]="'tenants.list.subtitle' | transloco: {installation: installationName()}"
            >
                <button mat-flat-button (click)="openCreateDialog()">
                    <mat-icon>add</mat-icon>
                    {{ "tenants.list.create" | transloco }}
                </button>
            </app-page-header>

            <div class="app-panel">
                <div class="app-panel-head">
                    <div class="app-search">
                        <mat-icon>search</mat-icon>
                        <input
                            type="search"
                            [placeholder]="'tenants.list.searchPlaceholder' | transloco"
                            [attr.aria-label]="'tenants.list.searchLabel' | transloco"
                            (input)="search($any($event.target).value)"
                        />
                    </div>
                    <span class="spacer"></span>
                    <button
                        mat-icon-button
                        (click)="refresh()"
                        [matTooltip]="'tenants.list.refresh' | transloco"
                        [attr.aria-label]="'tenants.list.refresh' | transloco"
                    >
                        <mat-icon>refresh</mat-icon>
                    </button>
                </div>

                @if (loading()) {
                    <mat-progress-bar mode="indeterminate" />
                }

                @if (tenants().length === 0 && !loading()) {
                    <app-empty-state
                        icon="apartment"
                        [title]="'tenants.list.emptyTitle' | transloco"
                        [text]="'tenants.list.emptyText' | transloco"
                    >
                        <button mat-flat-button (click)="openCreateDialog()">
                            <mat-icon>add</mat-icon>
                            {{ "tenants.list.create" | transloco }}
                        </button>
                    </app-empty-state>
                }

                <div class="table-container" [hidden]="tenants().length === 0">
                    <table mat-table [dataSource]="dataSource" [trackBy]="trackById" matSort class="tenant-table">
                        <ng-container matColumnDef="name">
                            <th mat-header-cell *matHeaderCellDef mat-sort-header>
                                {{ "tenants.list.columnTenant" | transloco }}
                            </th>
                            <td mat-cell *matCellDef="let tenant">
                                <div class="cell-main">
                                    <span class="tenant-mark">{{ tenant.name.charAt(0).toUpperCase() }}</span>
                                    <div>
                                        <div class="cell-title">{{ tenant.name }}</div>
                                        <div class="cell-sub mono">{{ tenant.databaseName }}</div>
                                    </div>
                                </div>
                            </td>
                        </ng-container>

                        <ng-container matColumnDef="active">
                            <th mat-header-cell *matHeaderCellDef mat-sort-header>
                                {{ "tenants.list.columnStatus" | transloco }}
                            </th>
                            <td mat-cell *matCellDef="let tenant">
                                <span class="app-chip" [class.ok]="tenant.active">
                                    {{ (tenant.active ? "common.active" : "common.inactive") | transloco }}
                                </span>
                            </td>
                        </ng-container>

                        <ng-container matColumnDef="contactEmail">
                            <th mat-header-cell *matHeaderCellDef mat-sort-header>
                                {{ "tenants.list.columnContact" | transloco }}
                            </th>
                            <td mat-cell *matCellDef="let tenant" class="muted">{{ tenant.contactEmail ?? "–" }}</td>
                        </ng-container>

                        <ng-container matColumnDef="globalKeysAllowed">
                            <th mat-header-cell *matHeaderCellDef>{{ "tenants.list.columnGlobalKeys" | transloco }}</th>
                            <td mat-cell *matCellDef="let tenant">
                                @if (tenant.globalKeysAllowed) {
                                    <span class="app-tag">{{ "tenants.list.allowed" | transloco }}</span>
                                } @else {
                                    <span class="muted">–</span>
                                }
                            </td>
                        </ng-container>

                        <ng-container matColumnDef="actions">
                            <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                            <td mat-cell *matCellDef="let tenant" class="actions-column">
                                <button
                                    mat-icon-button
                                    [matMenuTriggerFor]="rowMenu"
                                    [matMenuTriggerData]="{tenant}"
                                    (click)="$event.stopPropagation()"
                                    [attr.aria-label]="'common.more' | transloco"
                                >
                                    <mat-icon>more_vert</mat-icon>
                                </button>
                            </td>
                        </ng-container>

                        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                        <tr
                            mat-row
                            *matRowDef="let row; columns: displayedColumns"
                            class="clickable"
                            tabindex="0"
                            (click)="openTenant(row)"
                            (keydown.enter)="openTenant(row)"
                        ></tr>
                        <tr class="mat-row" *matNoDataRow>
                            <td class="mat-cell no-data" [attr.colspan]="displayedColumns.length">
                                {{ "tenants.list.noMatch" | transloco }}
                            </td>
                        </tr>
                    </table>
                </div>
                <mat-paginator
                    [hidden]="tenants().length === 0"
                    [pageSizeOptions]="[10, 25, 50, 100]"
                    showFirstLastButtons
                ></mat-paginator>
            </div>
        </div>

        <mat-menu #rowMenu="matMenu" xPosition="before">
            <ng-template matMenuContent let-tenant="tenant">
                <button mat-menu-item (click)="openTenant(tenant)">
                    <mat-icon>open_in_new</mat-icon>
                    <span>{{ "tenants.list.open" | transloco }}</span>
                </button>
                <button mat-menu-item (click)="openEditDialog(tenant)">
                    <mat-icon>edit</mat-icon>
                    <span>{{ "common.edit" | transloco }}</span>
                </button>
                <button mat-menu-item class="danger" (click)="confirmDelete(tenant)">
                    <mat-icon>delete</mat-icon>
                    <span>{{ "common.delete" | transloco }}</span>
                </button>
            </ng-template>
        </mat-menu>
    `,
    styles: [
        `
            .tenant-table {
                width: 100%;
            }

            .tenant-mark {
                width: 30px;
                height: 30px;
                border-radius: 8px;
                background: var(--app-brand-soft);
                color: var(--app-brand-text);
                display: grid;
                place-items: center;
                font-weight: 700;
                font-size: 13px;
                flex: 0 0 auto;
            }

            .no-data {
                padding: 16px;
                color: var(--app-text-3);
            }

            .danger,
            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class TenantListComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private router = inject(Router);
    private transloco = inject(TranslocoService);

    readonly tenants = this.store.selectSignal(TenantSelectors.selectAllTenants);
    readonly installationName = this.store.selectSignal(BrandingSelectors.selectInstallationName);
    readonly loading = this.store.selectSignal(TenantSelectors.selectTenantLoading);

    readonly displayedColumns = ["name", "active", "contactEmail", "globalKeysAllowed", "actions"];
    readonly dataSource = new MatTableDataSource<Tenant>([]);
    readonly trackById = (_index: number, tenant: Tenant) => tenant.id;

    private sort = viewChild.required(MatSort);
    private paginator = viewChild.required(MatPaginator);

    constructor() {
        this.store.dispatch(TenantActions.loadTenants());

        effect(() => {
            this.dataSource.data = this.tenants();
        });
        effect(() => {
            this.dataSource.sort = this.sort();
            this.dataSource.paginator = this.paginator();
        });

        this.notifyOn(TenantActions.createTenantSuccess, () => this.transloco.translate("tenants.snack.created"));
        this.notifyOn(TenantActions.updateTenantSuccess, () => this.transloco.translate("tenants.snack.updated"));
        this.notifyOn(TenantActions.deleteTenantSuccess, () => this.transloco.translate("tenants.snack.deleted"));
        this.notifyOn(
            TenantActions.createTenantFailure,
            ({error}) => this.transloco.translate("tenants.snack.createFailed", {error}),
            true,
        );
        this.notifyOn(
            TenantActions.updateTenantFailure,
            ({error}) => this.transloco.translate("tenants.snack.updateFailed", {error}),
            true,
        );
        this.notifyOn(
            TenantActions.deleteTenantFailure,
            ({error}) => this.transloco.translate("tenants.snack.deleteFailed", {error}),
            true,
        );
    }

    refresh(): void {
        this.store.dispatch(TenantActions.loadTenants());
    }

    openTenant(tenant: Tenant): void {
        this.router.navigate(["/dashboard/tenants", tenant.id, "overview"]).then();
    }

    search(text: string): void {
        this.dataSource.filter = text.trim().toLowerCase();
    }

    openCreateDialog(): void {
        this.openDialog(null, {
            action: tenant => TenantActions.createTenant({tenant}),
            success: [TenantActions.createTenantSuccess],
            failure: TenantActions.createTenantFailure,
        });
    }

    openEditDialog(existing: Tenant): void {
        this.openDialog(existing, {
            action: tenant => TenantActions.updateTenant({id: existing.id, tenant}),
            success: [TenantActions.updateTenantSuccess],
            failure: TenantActions.updateTenantFailure,
        });
    }

    confirmDelete(tenant: Tenant): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("tenants.list.confirmDelete", {name: tenant.name}),
                    confirmLabel: this.transloco.translate("common.delete"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(TenantActions.deleteTenant({id: tenant.id})));
    }

    private openDialog(data: Tenant | null, spec: DialogSaveSpec<TenantRequest>): void {
        this.dialogSave.open<TenantDialogComponent, Tenant | null, TenantRequest>(
            TenantDialogComponent,
            {width: "520px", data},
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
