import {Component, computed, inject} from "@angular/core";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import * as AccountSelectors from "../../store/account/account.selectors";
import {ProviderKeyListComponent} from "../provider-key-list/provider-key-list.component";
import {ApiKeysPanelComponent} from "../shared/api-keys-panel/api-keys-panel.component";
import {McpKeyPanelComponent} from "../shared/mcp-key-panel/mcp-key-panel.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";

/**
 * Tenant admin's page "API-Keys": the company's keys at the AI providers, the keys of its client software and its MCP
 * key for Claude Code.
 */
@Component({
    selector: "app-tenant-keys-page",
    imports: [
        TranslocoPipe,
        ProviderKeyListComponent,
        ApiKeysPanelComponent,
        McpKeyPanelComponent,
        PageHeaderComponent,
    ],
    template: `
        <div class="page">
            <app-page-header [title]="'tenantKeys.title' | transloco" [subtitle]="'tenantKeys.subtitle' | transloco" />
            <app-provider-key-list scope="self" />
            <app-api-keys-panel scope="self" [withTasks]="tasksAllowed()" />
            <app-mcp-key-panel scope="self" [withTasks]="tasksAllowed()" />
        </div>
    `,
})
export class TenantKeysPageComponent {
    private readonly account = inject(Store).selectSignal(AccountSelectors.selectAccount);
    readonly tasksAllowed = computed(() => this.account()?.tasksAllowed ?? false);
}
