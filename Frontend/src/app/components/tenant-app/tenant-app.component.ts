import {Component, computed, DestroyRef, inject} from "@angular/core";
import {RouterOutlet} from "@angular/router";
import {Store} from "@ngrx/store";
import {ROLE_TENANT_ADMIN} from "../../models/auth.model";
import * as AuthActions from "../../store/auth/auth.actions";
import * as AuthSelectors from "../../store/auth/auth.selectors";
import * as AccountActions from "../../store/account/account.actions";
import * as AccountSelectors from "../../store/account/account.selectors";
import * as BackendHealthActions from "../../store/backend-health/backend-health.actions";
import * as BackendHealthSelectors from "../../store/backend-health/backend-health.selectors";
import * as BrandingSelectors from "../../store/branding/branding.selectors";
import * as BrandingActions from "../../store/branding/branding.actions";
import * as DocSiteActions from "../../store/doc-site/doc-site.actions";
import * as DocSiteSelectors from "../../store/doc-site/doc-site.selectors";
import {ShellNavGroup} from "../../utils/shell.util";
import {AppShellComponent} from "../shared/app-shell/app-shell.component";
import {TranslocoPipe} from "@jsverse/transloco";
import {TenantChoice} from "../../models/auth.model";
import {DialogSave} from "../shared/dialog-save";
import {SwitchTenantDialogComponent} from "./switch-tenant-dialog.component";

/** Shell of the tenant area (/app): what a tenant's employees see after logging in. */
@Component({
    selector: "app-tenant-app",
    imports: [RouterOutlet, AppShellComponent, TranslocoPipe],
    template: `
        <app-shell
            [name]="branding().name"
            [subtitle]="subtitle()"
            [logoUrl]="logoUrl()"
            [navGroups]="navGroups()"
            [userName]="account()?.displayName ?? ''"
            [userRole]="(tenantAdmin() ? 'shell.roleAdmin' : 'shell.roleUser') | transloco"
            [backendReachable]="backendReachable()"
            [tenants]="account()?.tenants ?? []"
            [currentTenantId]="account()?.tenantId ?? null"
            (switchTenant)="switchTenant($event)"
            (logout)="logout()"
            (retryHealth)="retryBackendHealthCheck()"
        >
            <router-outlet />
        </app-shell>
    `,
})
export class TenantAppComponent {
    private store = inject(Store);
    readonly account = this.store.selectSignal(AccountSelectors.selectAccount);
    /** The tenant's own look where the admin set one - loaded below, applied by BrandingEffects. */
    readonly branding = this.store.selectSignal(BrandingSelectors.selectBranding);
    readonly logoUrl = this.store.selectSignal(BrandingSelectors.selectLogoUrl);
    readonly backendReachable = this.store.selectSignal(BackendHealthSelectors.selectBackendReachable);

    private readonly roles = this.store.selectSignal(AuthSelectors.selectAuthRoles);
    private readonly wamEnabled = this.store.selectSignal(AuthSelectors.selectWamEnabled);
    private readonly dialogSave = inject(DialogSave);
    private readonly handbook = this.store.selectSignal(DocSiteSelectors.selectHandbook);
    readonly tenantAdmin = computed(() => this.roles().includes(ROLE_TENANT_ADMIN));
    /** The company's name below the brand's - unless the brand is already called so. */
    readonly subtitle = computed(() => {
        const tenantName = this.account()?.tenantName;
        return tenantName && tenantName !== this.branding().name ? tenantName : null;
    });

    // The settings only for a tenant admin. New pages: add a lazy child route under "app" in app.routes.ts
    // (tenantAdminGuard for a settings page) and an entry here (labels are translation keys).
    readonly navGroups = computed<ShellNavGroup[]>(() => {
        const work: ShellNavGroup = {
            label: this.tenantAdmin() ? "nav.work" : null,
            items: [
                {path: "/app/chat", label: "nav.chat", icon: "forum"},
                // Only with a website to read - a tenant admin always, to upload the first one.
                ...(this.handbook().length > 0 || this.tenantAdmin()
                    ? [{path: "/app/handbook", label: "nav.handbook", icon: "auto_stories"}]
                    : []),
                {path: "/app/account", label: "nav.account", icon: "manage_accounts"},
            ],
        };
        if (!this.tenantAdmin()) {
            return [work];
        }
        return [
            work,
            {
                label: "nav.manage",
                items: [
                    {path: "/app/documents", label: "nav.knowledge", icon: "menu_book"},
                    {path: "/app/assistant", label: "nav.assistant", icon: "psychology"},
                    {path: "/app/users", label: "nav.users", icon: "group"},
                    {path: "/app/keys", label: "nav.apiKeys", icon: "key"},
                    {path: "/app/software", label: "nav.software", icon: "hub"},
                    {path: "/app/file-stores", label: "nav.fileStores", icon: "folder_shared"},
                    // Only when the admin let the tenant have tasks.
                    ...(this.account()?.tasksAllowed
                        ? [{path: "/app/tasks", label: "nav.tasks", icon: "schedule"}]
                        : []),
                    {path: "/app/usage", label: "nav.usage", icon: "receipt_long"},
                ],
            },
        ];
    });

    constructor() {
        this.store.dispatch(AccountActions.loadAccount());
        // After a reload the tenant area must know whether the switch asks a password.
        this.store.dispatch(AuthActions.loadWamStatus());
        this.store.dispatch(BrandingActions.loadTenantBranding());
        this.store.dispatch(DocSiteActions.loadHandbook());
        this.store.dispatch(BackendHealthActions.startBackendHealthPolling());
        this.store.dispatch(BackendHealthActions.checkBackendHealth());

        // The polling effect keeps running until it sees its explicit stop action.
        inject(DestroyRef).onDestroy(() => this.store.dispatch(BackendHealthActions.stopBackendHealthPolling()));
    }

    /**
     * With WAM the proxy's header proves the person - switched at once; without, the target user's password is asked
     * in a dialog that stays open until the switch is done.
     */
    switchTenant(tenant: TenantChoice): void {
        if (this.wamEnabled()) {
            this.store.dispatch(AuthActions.switchTenant({tenant}));
            return;
        }
        this.dialogSave.open(
            SwitchTenantDialogComponent,
            {data: tenant},
            {
                action: (password: string) => AuthActions.switchTenant({tenant, password}),
                success: [AuthActions.switchTenantSuccess],
                failure: AuthActions.switchTenantFailure,
            },
        );
    }

    logout(): void {
        this.store.dispatch(AuthActions.logout({byUser: true}));
    }

    retryBackendHealthCheck(): void {
        this.store.dispatch(BackendHealthActions.checkBackendHealth());
    }
}
