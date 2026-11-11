import {Component} from "@angular/core";
import {TranslocoPipe} from "@jsverse/transloco";
import {McpConnectionsPanelComponent} from "../shared/mcp-connections-panel/mcp-connections-panel.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";

/** Tenant admin's page "Software": the company's software the assistant uses in the chat, through its MCP server. */
@Component({
    selector: "app-software-page",
    imports: [McpConnectionsPanelComponent, PageHeaderComponent, TranslocoPipe],
    template: `
        <div class="page">
            <app-page-header
                [title]="'software.page.title' | transloco"
                [subtitle]="'software.page.subtitle' | transloco"
            />
            <app-mcp-connections-panel scope="self" />
        </div>
    `,
})
export class SoftwarePageComponent {}
