import {Component, input} from "@angular/core";
import {MatIconModule} from "@angular/material/icon";

/** What an empty list shows instead of a blank table: an icon, what is missing, how to add the first one (projected). */
@Component({
    selector: "app-empty-state",
    imports: [MatIconModule],
    template: `
        <div class="icon">
            <mat-icon>{{ icon() }}</mat-icon>
        </div>
        <h3>{{ title() }}</h3>
        @if (text()) {
            <p>{{ text() }}</p>
        }
        <ng-content />
    `,
    styles: [
        `
            :host {
                display: flex;
                flex-direction: column;
                align-items: center;
                gap: 6px;
                text-align: center;
                padding: 48px 24px;
            }

            .icon {
                width: 56px;
                height: 56px;
                border-radius: 16px;
                background: var(--app-brand-soft);
                color: var(--app-brand-text);
                display: grid;
                place-items: center;
                margin-bottom: 8px;
            }

            .icon mat-icon {
                font-size: 28px;
                width: 28px;
                height: 28px;
            }

            h3 {
                margin: 0;
                font-size: 16px;
                font-weight: 600;
            }

            p {
                margin: 0 0 12px;
                color: var(--app-text-2);
                max-width: 46ch;
            }
        `,
    ],
})
export class EmptyStateComponent {
    readonly icon = input.required<string>();
    readonly title = input.required<string>();
    readonly text = input<string | null>(null);
}
