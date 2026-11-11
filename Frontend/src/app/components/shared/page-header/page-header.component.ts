import {Component, input} from "@angular/core";
import {RouterLink} from "@angular/router";
import {MatIconModule} from "@angular/material/icon";
import {TranslocoPipe} from "@jsverse/transloco";

/**
 * The head of every page: title and a sentence on what the page is for on the left, the page's actions (projected,
 * the main one last as a filled button) on the right. With backLink a detail page leads back to its list.
 */
@Component({
    selector: "app-page-header",
    imports: [RouterLink, MatIconModule, TranslocoPipe],
    template: `
        @if (backLink(); as link) {
            <a class="back" [routerLink]="link">
                <mat-icon>arrow_back</mat-icon>
                {{ backLabel() ?? ("common.back" | transloco) }}
            </a>
        }
        <div class="row">
            <ng-content select="[pageHeaderLead]" />
            <div class="titles">
                <h1>{{ title() }}</h1>
                @if (subtitle()) {
                    <p>{{ subtitle() }}</p>
                }
                <ng-content select="[pageHeaderMeta]" />
            </div>
            <div class="actions">
                <ng-content />
            </div>
        </div>
    `,
    styles: [
        `
            :host {
                display: block;
                margin-bottom: 6px;
            }

            .back {
                display: inline-flex;
                align-items: center;
                gap: 4px;
                color: var(--app-text-2);
                text-decoration: none;
                font-size: 13px;
                margin-bottom: 10px;
            }

            .back:hover {
                color: var(--app-text);
            }

            .back mat-icon {
                font-size: 18px;
                width: 18px;
                height: 18px;
            }

            .row {
                display: flex;
                flex-wrap: wrap;
                align-items: flex-end;
                gap: 16px;
            }

            .titles {
                flex: 1 1 320px;
                min-width: 0;
            }

            h1 {
                margin: 0;
                font-size: 22px;
                line-height: 1.3;
                font-weight: 650;
                letter-spacing: -0.015em;
                text-wrap: balance;
            }

            p {
                margin: 4px 0 0;
                color: var(--app-text-2);
                max-width: 75ch;
            }

            .actions {
                display: flex;
                flex-wrap: wrap;
                gap: 8px;
            }

            .actions:empty {
                display: none;
            }
        `,
    ],
})
export class PageHeaderComponent {
    readonly title = input.required<string>();
    readonly subtitle = input<string | null>(null);
    readonly backLink = input<string | null>(null);
    /** The back link's text, translated by the caller; without it "Back". */
    readonly backLabel = input<string | null>(null);
}
