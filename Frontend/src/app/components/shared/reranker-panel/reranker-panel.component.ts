import {Component, effect, input, output} from "@angular/core";
import {FormControl, ReactiveFormsModule} from "@angular/forms";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatSelectModule} from "@angular/material/select";
import {TranslocoPipe} from "@jsverse/transloco";

/** A reranker the select offers. */
export interface RerankerChoice {
    id: number;
    displayName: string;
}

/**
 * The reranker of a tenant's knowledge search, or none - the same panel for the tenant admin's page "Firmenwissen" and
 * the admin's tab. Switching it embeds nothing, so it needs no confirmation; the page saves it. Shown only once the
 * admin assigned a reranker to the tenant (or one is still chosen).
 */
@Component({
    selector: "app-reranker-panel",
    imports: [ReactiveFormsModule, MatFormFieldModule, MatSelectModule, TranslocoPipe],
    template: `
        @if (models().length > 0 || chosenId() !== null) {
            <div class="app-panel">
                <div class="app-panel-head">
                    <span class="app-panel-title">{{ "documents.reranker.title" | transloco }}</span>
                    <span class="muted">{{ "documents.reranker.hint" | transloco }}</span>
                </div>
                <div class="app-panel-body">
                    <mat-form-field appearance="outline" class="reranker-select">
                        <mat-label>{{ "documents.reranker.label" | transloco }}</mat-label>
                        <mat-select [formControl]="control" (selectionChange)="chosen.emit($event.value)">
                            <mat-option [value]="null">{{ "documents.reranker.off" | transloco }}</mat-option>
                            @for (model of models(); track model.id) {
                                <mat-option [value]="model.id">{{ model.displayName }}</mat-option>
                            }
                        </mat-select>
                        @if (saving()) {
                            <mat-hint>{{ "documents.reranker.saving" | transloco }}</mat-hint>
                        }
                    </mat-form-field>
                </div>
            </div>
        }
    `,
    styles: [
        `
            .reranker-select {
                width: min(480px, 100%);
                margin-bottom: 0 !important;
            }
        `,
    ],
})
export class RerankerPanelComponent {
    readonly models = input.required<RerankerChoice[]>();
    /** The stored reranker; null: off (or not loaded yet). */
    readonly chosenId = input.required<number | null>();
    readonly saving = input(false);
    readonly chosen = output<number | null>();

    readonly control = new FormControl<number | null>(null);

    constructor() {
        // The select shows the stored reranker once nothing is being saved - after a failed change the previous one.
        effect(() => {
            if (!this.saving()) {
                this.control.setValue(this.chosenId());
            }
        });
    }
}
