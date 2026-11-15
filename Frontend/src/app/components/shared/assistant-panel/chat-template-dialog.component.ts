import {Component, inject} from "@angular/core";
import {AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators} from "@angular/forms";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatSelectModule} from "@angular/material/select";
import {TranslocoPipe} from "@jsverse/transloco";
import {
    ANSWER_SOURCE_LABELS,
    ANSWER_SOURCES,
    AnswerSource,
    ChatTemplate,
    ChatTemplateRequest,
    CREATIVITIES,
    Creativity,
    CREATIVITY_LABELS,
    MAX_INSTRUCTIONS,
    MAX_SEARCH_RESULTS,
    MAX_TEMPLATE_DESCRIPTION,
    MAX_TEMPLATE_NAME,
    MIN_SEARCH_RESULTS,
    Reasoning,
    REASONING_LABELS,
    REASONINGS,
} from "../../../models/assistant.model";
import {buildChatTemplateRequest, isSearchResults} from "../../../utils/assistant.util";
import {notBlank} from "../../../utils/validators.util";
import {dialogSave} from "../dialog-save";

function emptyOrSearchResults(control: AbstractControl): ValidationErrors | null {
    const value = control.value as number | null;
    return value === null || `${value}` === "" || isSearchResults(value) ? null : {searchResults: true};
}

/**
 * Create/edit dialog of a chat template: its name, what it is for (for those who choose it from outside), its
 * instructions (next to the tenant's) and the settings it sets itself - "Standard" takes the tenant's.
 */
@Component({
    selector: "app-chat-template-dialog",
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>
            {{ (template ? "assistant.templateDialog.titleEdit" : "assistant.templateDialog.titleCreate") | transloco }}
        </h2>
        <mat-dialog-content>
            <form id="template-form" [formGroup]="form" class="template-form" (ngSubmit)="onSave()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "common.name" | transloco }}</mat-label>
                    <input
                        matInput
                        formControlName="name"
                        required
                        cdkFocusInitial
                        [placeholder]="'assistant.templateDialog.namePlaceholder' | transloco"
                    />
                    <mat-hint>{{ "assistant.templateDialog.nameHint" | transloco }}</mat-hint>
                    @if (form.controls.name.hasError("maxlength")) {
                        <mat-error>{{ "assistant.form.maxLength" | transloco: {max: maxName} }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "assistant.templateDialog.description" | transloco }}</mat-label>
                    <textarea matInput formControlName="description" rows="2"></textarea>
                    <mat-hint>
                        {{
                            "assistant.templateDialog.descriptionHint"
                                | transloco: {length: form.controls.description.value.length, max: maxDescription}
                        }}
                    </mat-hint>
                    @if (form.controls.description.hasError("maxlength")) {
                        <mat-error>{{ "assistant.form.maxLength" | transloco: {max: maxDescription} }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "assistant.templateDialog.instructions" | transloco }}</mat-label>
                    <textarea matInput formControlName="instructions" rows="6"></textarea>
                    <mat-hint>
                        {{
                            "assistant.templateDialog.instructionsHint"
                                | transloco: {length: form.controls.instructions.value.length, max: maxInstructions}
                        }}
                    </mat-hint>
                    @if (form.controls.instructions.hasError("maxlength")) {
                        <mat-error>{{ "assistant.form.maxLength" | transloco: {max: maxInstructions} }}</mat-error>
                    }
                </mat-form-field>
                <div class="row">
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "assistant.form.answerSource" | transloco }}</mat-label>
                        <mat-select formControlName="answerSource">
                            <mat-option value="">{{ "assistant.templateDialog.standard" | transloco }}</mat-option>
                            @for (source of answerSources; track source) {
                                <mat-option [value]="source">{{ answerSourceLabels[source] | transloco }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "assistant.form.creativity" | transloco }}</mat-label>
                        <mat-select formControlName="creativity">
                            <mat-option value="">{{ "assistant.templateDialog.standard" | transloco }}</mat-option>
                            @for (creativity of creativities; track creativity) {
                                <mat-option [value]="creativity">{{
                                    creativityLabels[creativity] | transloco
                                }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "assistant.form.reasoning" | transloco }}</mat-label>
                        <mat-select formControlName="reasoning">
                            <mat-option value="">{{ "assistant.templateDialog.standard" | transloco }}</mat-option>
                            @for (reasoning of reasonings; track reasoning) {
                                <mat-option [value]="reasoning">{{
                                    reasoningLabels[reasoning] | transloco
                                }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "assistant.form.searchResults" | transloco }}</mat-label>
                        <input
                            matInput
                            type="number"
                            formControlName="searchResults"
                            [min]="minResults"
                            [max]="maxResults"
                            [placeholder]="'assistant.templateDialog.standard' | transloco"
                        />
                        <mat-hint>{{
                            "assistant.templateDialog.searchResultsHint" | transloco: {min: minResults, max: maxResults}
                        }}</mat-hint>
                        @if (form.controls.searchResults.hasError("searchResults")) {
                            <mat-error>{{
                                "assistant.form.range" | transloco: {min: minResults, max: maxResults}
                            }}</mat-error>
                        }
                    </mat-form-field>
                </div>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="template-form" [disabled]="form.invalid || saver.saving()">
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .template-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(620px, 80vw);
                margin-top: 16px;
            }

            .row {
                display: grid;
                grid-template-columns: 1fr 1fr;
                gap: 12px;
            }

            mat-form-field {
                width: 100%;
            }
        `,
    ],
})
export class ChatTemplateDialogComponent {
    private fb = inject(FormBuilder);
    private dialogRef = inject<MatDialogRef<ChatTemplateDialogComponent, ChatTemplateRequest>>(MatDialogRef);
    readonly saver = dialogSave<ChatTemplateRequest>();
    readonly template = inject<ChatTemplate | null>(MAT_DIALOG_DATA);

    readonly answerSources = ANSWER_SOURCES;
    readonly creativities = CREATIVITIES;
    readonly reasonings = REASONINGS;
    readonly answerSourceLabels = ANSWER_SOURCE_LABELS;
    readonly creativityLabels = CREATIVITY_LABELS;
    readonly reasoningLabels = REASONING_LABELS;
    readonly maxName = MAX_TEMPLATE_NAME;
    readonly maxInstructions = MAX_INSTRUCTIONS;
    readonly maxDescription = MAX_TEMPLATE_DESCRIPTION;
    readonly minResults = MIN_SEARCH_RESULTS;
    readonly maxResults = MAX_SEARCH_RESULTS;

    form = this.fb.nonNullable.group({
        name: [this.template?.name ?? "", [Validators.required, notBlank(), Validators.maxLength(MAX_TEMPLATE_NAME)]],
        instructions: [this.template?.instructions ?? "", [Validators.maxLength(MAX_INSTRUCTIONS)]],
        description: [this.template?.description ?? "", [Validators.maxLength(MAX_TEMPLATE_DESCRIPTION)]],
        answerSource: [(this.template?.answerSource ?? "") as AnswerSource | ""],
        creativity: [(this.template?.creativity ?? "") as Creativity | ""],
        reasoning: [(this.template?.reasoning ?? "") as Reasoning | ""],
        searchResults: this.fb.control<number | null>(this.template?.searchResults ?? null, [emptyOrSearchResults]),
    });

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        this.saver.submit(buildChatTemplateRequest(this.form.getRawValue()));
    }
}
