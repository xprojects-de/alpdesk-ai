import {Component, inject} from "@angular/core";
import {ActivatedRoute, RouterLink} from "@angular/router";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {EmptyStateComponent} from "../shared/empty-state/empty-state.component";
import {TranslocoPipe} from "@jsverse/transloco";

@Component({
    selector: "app-not-found",
    imports: [RouterLink, MatButtonModule, MatIconModule, EmptyStateComponent, TranslocoPipe],
    template: `
        <div class="page">
            <div class="app-panel">
                <app-empty-state
                    icon="search_off"
                    [title]="'notFound.title' | transloco"
                    [text]="'notFound.text' | transloco"
                >
                    <!-- Points at the area's root rather than a specific leaf route (e.g. /dashboard/tenants),
                         so this stays correct on its own if the default landing page ever changes - that's
                         decided once by the {path: '', redirectTo: ...} child route in app.routes.ts. -->
                    <a mat-flat-button [routerLink]="home">
                        <mat-icon>home</mat-icon>
                        {{ "notFound.home" | transloco }}
                    </a>
                </app-empty-state>
            </div>
        </div>
    `,
})
/** The catch-all child of an area (admin dashboard, tenant app); the route's `data.home` is the area's root. */
export class NotFoundComponent {
    readonly home: string = inject(ActivatedRoute).snapshot.data["home"] ?? "/";
}
