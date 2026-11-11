import {Component, inject} from "@angular/core";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {TenantUsersPanelComponent} from "../shared/tenant-users-panel/tenant-users-panel.component";
import {UserGroupsPanelComponent} from "../shared/user-groups-panel/user-groups-panel.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {LoginLinkComponent} from "../shared/login-link/login-link.component";
import * as BrandingActions from "../../store/branding/branding.actions";
import * as BrandingSelectors from "../../store/branding/branding.selectors";

/**
 * Tenant admin's page "Benutzer": the users and groups of the own tenant - the same panels the admin has in its tab -
 * and the address of the tenant's own login page to hand to them.
 */
@Component({
    selector: "app-tenant-users-page",
    imports: [
        TranslocoPipe,
        TenantUsersPanelComponent,
        UserGroupsPanelComponent,
        PageHeaderComponent,
        LoginLinkComponent,
    ],
    template: `
        <div class="page">
            <app-page-header
                [title]="'users.page.title' | transloco"
                [subtitle]="'users.page.subtitle' | transloco: {name: branding().name}"
            />
            @if (loginKey(); as loginKey) {
                <div class="app-panel">
                    <div class="app-panel-head">
                        <span class="app-panel-title">{{ "branding.loginLink.title" | transloco }}</span>
                        <span class="muted">{{ "branding.loginLink.usersIntro" | transloco }}</span>
                    </div>
                    <div class="app-panel-body">
                        <app-login-link [loginKey]="loginKey" />
                    </div>
                </div>
            }
            <app-tenant-users-panel scope="self" />
            <app-user-groups-panel scope="self" />
        </div>
    `,
})
export class TenantUsersPageComponent {
    private store = inject(Store);

    readonly branding = this.store.selectSignal(BrandingSelectors.selectBranding);
    readonly loginKey = this.store.selectSignal(BrandingSelectors.selectOwnLoginKey);

    constructor() {
        this.store.dispatch(BrandingActions.loadOwnLoginKey());
    }
}
