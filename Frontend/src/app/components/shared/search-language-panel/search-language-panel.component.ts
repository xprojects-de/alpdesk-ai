import {Component, effect, input, output} from "@angular/core";
import {FormControl, ReactiveFormsModule} from "@angular/forms";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatSelectModule} from "@angular/material/select";
import {TranslocoPipe} from "@jsverse/transloco";
import {SEARCH_LANGUAGES, SearchLanguage} from "../../../models/tenant.model";

/**
 * The language the full-text search reads a tenant's documents in - the same panel for the tenant admin's page
 * "Firmenwissen" and the admin's tab. Choosing one builds the search again and embeds nothing, so it needs no
 * confirmation; the page saves it.
 */
@Component({
    selector: "app-search-language-panel",
    imports: [ReactiveFormsModule, MatFormFieldModule, MatSelectModule, TranslocoPipe],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "documents.searchLanguage.title" | transloco }}</span>
                <span class="muted">{{ "documents.searchLanguage.hint" | transloco }}</span>
            </div>
            <div class="app-panel-body">
                <mat-form-field appearance="outline" class="language-select">
                    <mat-label>{{ "documents.searchLanguage.label" | transloco }}</mat-label>
                    <mat-select [formControl]="control" (selectionChange)="chosen.emit($event.value)">
                        @for (choice of languages; track choice) {
                            <mat-option [value]="choice">{{ "searchLanguages." + choice | transloco }}</mat-option>
                        }
                    </mat-select>
                    @if (saving()) {
                        <mat-hint>{{ "documents.searchLanguage.saving" | transloco }}</mat-hint>
                    }
                </mat-form-field>
            </div>
        </div>
    `,
    styles: [
        `
            .language-select {
                width: min(480px, 100%);
                margin-bottom: 0 !important;
            }
        `,
    ],
})
export class SearchLanguagePanelComponent {
    /** The stored language; null while it is not loaded. */
    readonly language = input.required<SearchLanguage | null>();
    readonly saving = input(false);
    readonly chosen = output<SearchLanguage>();

    readonly languages = SEARCH_LANGUAGES;
    readonly control = new FormControl<SearchLanguage | null>(null);

    constructor() {
        // The select shows the stored language once nothing is being saved: the new one, or after a failed change the
        // previous one again. While saving it keeps the choice.
        effect(() => {
            if (!this.saving()) {
                this.control.setValue(this.language());
            }
        });
    }
}
