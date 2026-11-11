import {Component, computed, DestroyRef, inject, input} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {Router, RouterLink} from "@angular/router";
import {ActionCreator, Creator, Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {filter} from "rxjs/operators";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatMenuModule} from "@angular/material/menu";
import {MatTabsModule} from "@angular/material/tabs";
import {MatDialog} from "@angular/material/dialog";
import {MatSnackBar} from "@angular/material/snack-bar";
import {Tenant, TenantRequest} from "../../models/tenant.model";
import {TenantBrandingRequest} from "../../models/branding.model";
import * as TenantActions from "../../store/tenant/tenant.actions";
import * as TenantSelectors from "../../store/tenant/tenant.selectors";
import * as TenantModelActions from "../../store/tenant-model/tenant-model.actions";
import * as BrandingActions from "../../store/branding/branding.actions";
import * as BrandingSelectors from "../../store/branding/branding.selectors";
import {ConfirmDialogComponent, ConfirmDialogData} from "../shared/confirm-dialog/confirm-dialog.component";
import {DialogSave} from "../shared/dialog-save";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {TenantUsersPanelComponent} from "../shared/tenant-users-panel/tenant-users-panel.component";
import {UserGroupsPanelComponent} from "../shared/user-groups-panel/user-groups-panel.component";
import {AssistantPanelComponent} from "../shared/assistant-panel/assistant-panel.component";
import {ApiKeysPanelComponent} from "../shared/api-keys-panel/api-keys-panel.component";
import {McpKeyPanelComponent} from "../shared/mcp-key-panel/mcp-key-panel.component";
import {McpConnectionsPanelComponent} from "../shared/mcp-connections-panel/mcp-connections-panel.component";
import {DocSitesPanelComponent} from "../shared/doc-sites-panel/doc-sites-panel.component";
import {FileStoresPanelComponent} from "../shared/file-stores-panel/file-stores-panel.component";
import {TasksPanelComponent} from "../shared/tasks-panel/tasks-panel.component";
import {ProviderKeyListComponent} from "../provider-key-list/provider-key-list.component";
import {TenantDialogComponent} from "../tenant-list/tenant-dialog.component";
import {TenantModelsDialogComponent} from "../tenant-list/tenant-models-dialog.component";
import {TenantBrandingDialogComponent} from "../tenant-list/tenant-branding-dialog.component";
import {TenantOverviewComponent} from "./tenant-overview.component";
import {TenantModelsSummaryComponent} from "./tenant-models-summary.component";
import {TenantKnowledgeComponent} from "./tenant-knowledge.component";
import {TenantBrandingSummaryComponent} from "./tenant-branding-summary.component";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";

const TABS = [
    {key: "overview", label: "tenantDetail.tabs.overview", icon: "dashboard"},
    {key: "users", label: "tenantDetail.tabs.users", icon: "group"},
    {key: "models", label: "tenantDetail.tabs.models", icon: "smart_toy"},
    {key: "knowledge", label: "tenantDetail.tabs.knowledge", icon: "menu_book"},
    {key: "handbook", label: "tenantDetail.tabs.handbook", icon: "auto_stories"},
    {key: "assistant", label: "tenantDetail.tabs.assistant", icon: "psychology"},
    {key: "keys", label: "tenantDetail.tabs.keys", icon: "key"},
    {key: "software", label: "tenantDetail.tabs.software", icon: "hub"},
    {key: "fileStores", label: "tenantDetail.tabs.fileStores", icon: "folder_shared"},
    {key: "tasks", label: "tenantDetail.tabs.tasks", icon: "schedule"},
    {key: "branding", label: "tenantDetail.tabs.branding", icon: "palette"},
] as const;

/**
 * Admin page of one tenant (/dashboard/tenants/:id/:tab): everything the admin keeps for it, one tab per area. The
 * tabs show the same panels as the tenant admin's pages, with the tenant's id as scope; the tab is part of the
 * address, so a reload or a shared link opens the same tab.
 */
@Component({
    selector: "app-tenant-detail",
    imports: [
        RouterLink,
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatTabsModule,
        PageHeaderComponent,
        TenantOverviewComponent,
        TenantModelsSummaryComponent,
        TenantKnowledgeComponent,
        TenantBrandingSummaryComponent,
        TenantUsersPanelComponent,
        UserGroupsPanelComponent,
        AssistantPanelComponent,
        ApiKeysPanelComponent,
        McpKeyPanelComponent,
        McpConnectionsPanelComponent,
        DocSitesPanelComponent,
        FileStoresPanelComponent,
        TasksPanelComponent,
        ProviderKeyListComponent,
        TranslocoPipe,
    ],
    template: `
        <div class="page">
            @if (tenant(); as tenant) {
                <app-page-header
                    [title]="tenant.name"
                    backLink="/dashboard/tenants"
                    [backLabel]="'tenantDetail.allTenants' | transloco"
                >
                    <span pageHeaderLead class="tenant-mark">{{ tenant.name.charAt(0).toUpperCase() }}</span>
                    <div pageHeaderMeta class="meta">
                        <span class="app-chip" [class.ok]="tenant.active">{{
                            (tenant.active ? "common.active" : "common.inactive") | transloco
                        }}</span>
                        <span class="mono">{{ tenant.databaseName }}</span>
                        @if (tenant.contactEmail) {
                            <span>{{ tenant.contactEmail }}</span>
                        }
                    </div>
                    <button mat-stroked-button (click)="openEditDialog(tenant)">
                        <mat-icon>edit</mat-icon>
                        {{ "common.edit" | transloco }}
                    </button>
                    <button
                        mat-icon-button
                        [matMenuTriggerFor]="moreMenu"
                        [attr.aria-label]="'common.more' | transloco"
                    >
                        <mat-icon>more_vert</mat-icon>
                    </button>
                    <mat-menu #moreMenu="matMenu" xPosition="before">
                        <button mat-menu-item class="danger" (click)="confirmDelete(tenant)">
                            <mat-icon>delete</mat-icon>
                            <span>{{ "tenantDetail.delete" | transloco }}</span>
                        </button>
                    </mat-menu>
                </app-page-header>

                <nav mat-tab-nav-bar [tabPanel]="tabPanel" class="tabs" mat-stretch-tabs="false">
                    @for (item of tabs; track item.key) {
                        <a
                            mat-tab-link
                            [routerLink]="['/dashboard/tenants', tenant.id, item.key]"
                            [active]="shownTab() === item.key"
                            replaceUrl
                        >
                            <mat-icon>{{ item.icon }}</mat-icon>
                            {{ item.label | transloco }}
                        </a>
                    }
                </nav>

                <mat-tab-nav-panel #tabPanel class="tab-panel">
                    @switch (shownTab()) {
                        @case ("overview") {
                            <app-tenant-overview
                                [tenant]="tenant"
                                (editTenant)="openEditDialog(tenant)"
                                (editModels)="openModels(tenant)"
                            />
                        }
                        @case ("users") {
                            <p class="app-note">
                                <mat-icon>info</mat-icon>
                                {{ "tenantDetail.usersNote" | transloco: {installation: installationName()} }}
                            </p>
                            <app-tenant-users-panel [scope]="tenant.id" />
                            <app-user-groups-panel [scope]="tenant.id" />
                        }
                        @case ("models") {
                            <app-tenant-models-summary [tenant]="tenant" (edit)="openModels(tenant)" />
                        }
                        @case ("knowledge") {
                            <app-tenant-knowledge [tenant]="tenant" />
                        }
                        @case ("handbook") {
                            <app-doc-sites-panel [scope]="tenant.id" />
                        }
                        @case ("assistant") {
                            <app-assistant-panel [scope]="tenant.id" />
                        }
                        @case ("keys") {
                            <app-provider-key-list [scope]="tenant.id" />
                            <app-api-keys-panel [scope]="tenant.id" [withTasks]="tenant.tasksAllowed" />
                            <app-mcp-key-panel [scope]="tenant.id" [withTasks]="tenant.tasksAllowed" />
                        }
                        @case ("software") {
                            <app-mcp-connections-panel [scope]="tenant.id" />
                        }
                        @case ("fileStores") {
                            <app-file-stores-panel [scope]="tenant.id" />
                        }
                        @case ("tasks") {
                            @if (!tenant.tasksAllowed) {
                                <p class="app-note">
                                    <mat-icon>info</mat-icon>
                                    <span>{{ "tasks.notAllowedNote" | transloco }}</span>
                                </p>
                            }
                            <app-tasks-panel
                                [scope]="tenant.id"
                                [linkBase]="'/dashboard/tenants/' + tenant.id + '/tasks'"
                            />
                        }
                        @case ("branding") {
                            <app-tenant-branding-summary [tenant]="tenant" (edit)="openBranding(tenant)" />
                        }
                    }
                </mat-tab-nav-panel>
            } @else if (!loading()) {
                <app-page-header
                    [title]="'tenantDetail.notFoundTitle' | transloco"
                    [subtitle]="'tenantDetail.notFoundText' | transloco"
                    backLink="/dashboard/tenants"
                    [backLabel]="'tenantDetail.allTenants' | transloco"
                />
            }
        </div>
    `,
    styles: [
        `
            .tenant-mark {
                width: 44px;
                height: 44px;
                border-radius: 12px;
                background: var(--app-brand);
                color: var(--app-on-brand);
                display: grid;
                place-items: center;
                font-weight: 700;
                font-size: 18px;
                flex: 0 0 auto;
                align-self: center;
            }

            .meta {
                display: flex;
                flex-wrap: wrap;
                align-items: center;
                gap: 6px 12px;
                margin-top: 6px;
                color: var(--app-text-2);
                font-size: 13px;
            }

            .tabs {
                border-bottom: 1px solid var(--app-border);
                --mat-tab-container-divider-height: 0;
            }

            .tabs mat-icon {
                font-size: 18px;
                width: 18px;
                height: 18px;
                margin-right: 6px;
            }

            .tab-panel {
                display: flex;
                flex-direction: column;
                gap: 16px;
                padding-top: 4px;
            }

            .tab-panel .app-note {
                margin: 0;
            }

            .danger,
            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class TenantDetailComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);

    /** From the address (route parameters as inputs). */
    readonly id = input.required<string>();
    readonly tab = input<string>();

    readonly tabs = TABS;
    private readonly tenants = this.store.selectSignal(TenantSelectors.selectAllTenants);
    readonly loading = this.store.selectSignal(TenantSelectors.selectTenantLoading);
    readonly tenant = computed(() => this.tenants().find(tenant => tenant.id === Number(this.id())) ?? null);
    /** An unknown tab in the address shows the overview. */
    readonly shownTab = computed(() => TABS.find(item => item.key === this.tab())?.key ?? "overview");
    readonly installationName = this.store.selectSignal(BrandingSelectors.selectInstallationName);

    constructor() {
        // A reload or a link straight to this page: the list is not in the store yet.
        if (this.tenants().length === 0) {
            this.store.dispatch(TenantActions.loadTenants());
        }

        this.notifyOn(TenantActions.updateTenantSuccess, () => this.transloco.translate("tenants.snack.updated"));
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
        this.notifyOn(TenantModelActions.assignTenantModelsSuccess, () =>
            this.transloco.translate("tenantDetail.snack.modelsSaved"),
        );
        this.notifyOn(
            TenantModelActions.assignTenantModelsFailure,
            ({error}) => this.transloco.translate("tenantDetail.snack.modelsFailed", {error}),
            true,
        );
        this.notifyOn(BrandingActions.saveAdminBrandingSuccess, () =>
            this.transloco.translate("tenantDetail.snack.brandingSaved"),
        );
        this.notifyOn(
            BrandingActions.saveAdminBrandingFailure,
            ({error}) => this.transloco.translate("tenantDetail.snack.brandingFailed", {error}),
            true,
        );
        this.actions$.pipe(ofType(TenantActions.deleteTenantSuccess), takeUntilDestroyed()).subscribe(() => {
            this.snackBar.open(
                this.transloco.translate("tenants.snack.deleted"),
                this.transloco.translate("common.ok"),
                {duration: 3000},
            );
            this.router.navigate(["/dashboard/tenants"]).then();
        });
    }

    openEditDialog(existing: Tenant): void {
        this.dialogSave.open<TenantDialogComponent, Tenant | null, TenantRequest>(
            TenantDialogComponent,
            {width: "520px", data: existing},
            {
                action: tenant => TenantActions.updateTenant({id: existing.id, tenant}),
                success: [TenantActions.updateTenantSuccess],
                failure: TenantActions.updateTenantFailure,
            },
        );
    }

    openModels(tenant: Tenant): void {
        this.dialogSave.open<TenantModelsDialogComponent, Tenant, number[]>(
            TenantModelsDialogComponent,
            {width: "560px", maxWidth: "95vw", data: tenant},
            {
                action: modelIds => TenantModelActions.assignTenantModels({tenantId: tenant.id, modelIds}),
                success: [TenantModelActions.assignTenantModelsSuccess],
                failure: TenantModelActions.assignTenantModelsFailure,
            },
        );
    }

    openBranding(tenant: Tenant): void {
        this.dialogSave.open<TenantBrandingDialogComponent, Tenant, TenantBrandingRequest>(
            TenantBrandingDialogComponent,
            {width: "560px", maxWidth: "95vw", data: tenant},
            {
                action: request => BrandingActions.saveAdminBranding({tenantId: tenant.id, request}),
                success: [BrandingActions.saveAdminBrandingSuccess],
                failure: BrandingActions.saveAdminBrandingFailure,
            },
        );
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
