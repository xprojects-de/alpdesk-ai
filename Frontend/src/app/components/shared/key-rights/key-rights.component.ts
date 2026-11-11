import {Component, input} from "@angular/core";
import {TranslocoPipe} from "@jsverse/transloco";
import {KeySummary} from "../../../utils/key-form.util";

/** A key's rights in its table row, short: its areas, software (and where it may change), tasks, the web search - changed in its dialog. */
@Component({
    selector: "app-key-rights",
    imports: [TranslocoPipe],
    template: `
        <div class="rights">
            @if (summary().areas.length > 0) {
                <span class="app-tag">{{ "keyRights.areas" | transloco: {names: summary().areas.join(", ")} }}</span>
            } @else {
                <span class="app-tag muted">{{ "keyRights.companyKnowledge" | transloco }}</span>
            }
            @if (summary().software.length > 0) {
                <span class="app-tag">{{
                    "keyRights.software" | transloco: {names: summary().software.join(", ")}
                }}</span>
            }
            @if (summary().changing.length > 0) {
                <span class="app-tag">{{
                    "keyRights.changing" | transloco: {names: summary().changing.join(", ")}
                }}</span>
            }
            @if (summary().tasks.length > 0) {
                <span class="app-tag">{{ "keyRights.tasks" | transloco: {names: summary().tasks.join(", ")} }}</span>
            }
            @if (summary().webSearch) {
                <span class="app-tag">{{ "keyRights.webSearch" | transloco }}</span>
            }
            @if (keepsHistory() === false) {
                <span class="app-tag">{{ "keyRights.noHistory" | transloco }}</span>
            }
        </div>
    `,
    styles: [
        `
            .rights {
                display: flex;
                flex-wrap: wrap;
                gap: 4px;
                padding: 4px 0;
            }
        `,
    ],
})
export class KeyRightsComponent {
    readonly summary = input.required<KeySummary>();
    /** Only an API key: false shows "ohne Verlauf"; undefined for an MCP key. */
    readonly keepsHistory = input<boolean | undefined>(undefined);
}
