import {Component} from "@angular/core";
import {TranslocoPipe} from "@jsverse/transloco";
import {AssistantPanelComponent} from "../shared/assistant-panel/assistant-panel.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";

/** Tenant admin's page "Assistent": how the own tenant's assistant answers - the panel the admin has as a dialog. */
@Component({
    selector: "app-assistant-page",
    imports: [AssistantPanelComponent, PageHeaderComponent, TranslocoPipe],
    template: `
        <div class="page">
            <app-page-header
                [title]="'assistant.page.title' | transloco"
                [subtitle]="'assistant.page.subtitle' | transloco"
            />
            <app-assistant-panel scope="self" />
        </div>
    `,
})
export class AssistantPageComponent {}
