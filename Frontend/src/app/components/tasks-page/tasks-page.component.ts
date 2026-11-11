import {Component} from "@angular/core";
import {TranslocoPipe} from "@jsverse/transloco";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {TasksPanelComponent} from "../shared/tasks-panel/tasks-panel.component";

/** Tenant admin's page "Aufgaben": the tenant's scheduled tasks - checks of files that report what they find. */
@Component({
    selector: "app-tasks-page",
    imports: [PageHeaderComponent, TasksPanelComponent, TranslocoPipe],
    template: `
        <div class="page">
            <app-page-header [title]="'tasks.page.title' | transloco" [subtitle]="'tasks.page.subtitle' | transloco" />
            <app-tasks-panel scope="self" [linkBase]="'/app/tasks'" />
        </div>
    `,
})
export class TasksPageComponent {}
