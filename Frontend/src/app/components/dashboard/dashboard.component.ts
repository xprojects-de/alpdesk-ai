import {Component, computed, DestroyRef, inject} from "@angular/core";
import {RouterOutlet} from "@angular/router";
import {Store} from "@ngrx/store";
import * as AuthActions from "../../store/auth/auth.actions";
import * as AuthSelectors from "../../store/auth/auth.selectors";
import * as VersionActions from "../../store/version/version.actions";
import * as VersionSelectors from "../../store/version/version.selectors";
import * as BackendHealthActions from "../../store/backend-health/backend-health.actions";
import * as BackendHealthSelectors from "../../store/backend-health/backend-health.selectors";
import * as BrandingSelectors from "../../store/branding/branding.selectors";
import {ShellNavGroup} from "../../utils/shell.util";
import {AppShellComponent} from "../shared/app-shell/app-shell.component";
import {TranslocoPipe} from "@jsverse/transloco";

/** Shell of the admin's area (/dashboard): the installation's look. */
@Component({
    selector: "app-dashboard",
    imports: [RouterOutlet, AppShellComponent, TranslocoPipe],
    template: `
        <app-shell
            [name]="branding().name"
            [subtitle]="'shell.administration' | transloco"
            [logoUrl]="logoUrl()"
            [navGroups]="navGroups()"
            [userName]="username() ?? ''"
            [userRole]="'shell.administrator' | transloco"
            [version]="version()?.version ?? null"
            [versionTooltip]="version()?.application ?? ''"
            [backendReachable]="backendReachable()"
            (logout)="logout()"
            (retryHealth)="retryBackendHealthCheck()"
        >
            <router-outlet />
        </app-shell>
    `,
})
export class DashboardComponent {
    private store = inject(Store);
    readonly username = this.store.selectSignal(AuthSelectors.selectAuthUsername);
    readonly branding = this.store.selectSignal(BrandingSelectors.selectBranding);
    readonly logoUrl = this.store.selectSignal(BrandingSelectors.selectLogoUrl);
    readonly version = this.store.selectSignal(VersionSelectors.selectVersion);
    // null until the first health check completes (see BackendHealthState.reachable).
    readonly backendReachable = this.store.selectSignal(BackendHealthSelectors.selectBackendReachable);

    readonly wamEnabled = this.store.selectSignal(AuthSelectors.selectWamEnabled);

    // New pages: add a lazy child route in app.routes.ts and an entry here (labels are translation keys). The access
    // requests exist only with the WAM proxy.
    readonly navGroups = computed<ShellNavGroup[]>(() => [
        {
            label: "nav.tenants",
            items: [
                {path: "/dashboard/tenants", label: "nav.tenants", icon: "apartment"},
                ...(this.wamEnabled()
                    ? [{path: "/dashboard/access-requests", label: "nav.accessRequests", icon: "how_to_reg"}]
                    : []),
            ],
        },
        {
            label: "nav.ai",
            items: [
                {path: "/dashboard/models", label: "nav.models", icon: "psychology"},
                {path: "/dashboard/provider-keys", label: "nav.globalKeys", icon: "key"},
                {path: "/dashboard/usage", label: "nav.usage", icon: "receipt_long"},
            ],
        },
        {
            label: "nav.system",
            items: [{path: "/dashboard/application-log", label: "nav.applicationLog", icon: "monitor_heart"}],
        },
    ]);

    constructor() {
        this.store.dispatch(VersionActions.loadVersion());
        this.store.dispatch(BackendHealthActions.startBackendHealthPolling());
        this.store.dispatch(BackendHealthActions.checkBackendHealth());

        // The polling effect keeps running until it sees its explicit stop action.
        inject(DestroyRef).onDestroy(() => this.store.dispatch(BackendHealthActions.stopBackendHealthPolling()));
    }

    logout(): void {
        this.store.dispatch(AuthActions.logout({byUser: true}));
    }

    retryBackendHealthCheck(): void {
        this.store.dispatch(BackendHealthActions.checkBackendHealth());
    }
}
