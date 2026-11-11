import {Component, inject} from "@angular/core";
import {FormControl, FormGroup, ReactiveFormsModule, Validators} from "@angular/forms";
import {MatButtonModule} from "@angular/material/button";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {TranslocoPipe} from "@jsverse/transloco";
import {SoftwarePrompt} from "../../models/mcp.model";
import {softwarePromptLabel, softwarePromptValues} from "../../utils/software-prompt.util";
import {notBlank} from "../../utils/validators.util";
import {dialogSave} from "../shared/dialog-save";

/**
 * A prompt of the connected software: the user fills its arguments, the software makes its text of them, and the text
 * goes into the chat's input field - read and sent by the user. Stays open until the text came (a refused value or a
 * failing software keeps everything entered).
 */
@Component({
    selector: "app-chat-prompt-dialog",
    imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, TranslocoPipe],
    template: `
        <h2 mat-dialog-title>{{ label }}</h2>
        <mat-dialog-content>
            <p class="software">{{ "chat.prompts.from" | transloco: {software: prompt.software} }}</p>
            @if (prompt.description) {
                <p class="description">{{ prompt.description }}</p>
            }
            <form id="chat-prompt-form" [formGroup]="form" class="prompt-form" (ngSubmit)="onSave()">
                @for (argument of prompt.arguments; track argument.name) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ argument.name }}</mat-label>
                        <input matInput [formControlName]="argument.name" [required]="argument.required" />
                        @if (argument.description) {
                            <mat-hint>{{ argument.description }}</mat-hint>
                        }
                        @if (form.controls[argument.name].invalid && form.controls[argument.name].touched) {
                            <mat-error>{{ "chat.prompts.required" | transloco }}</mat-error>
                        }
                    </mat-form-field>
                } @empty {
                    <p class="muted">{{ "chat.prompts.noArguments" | transloco }}</p>
                }
            </form>
            <p class="note">{{ "chat.prompts.note" | transloco }}</p>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="chat-prompt-form" [disabled]="form.invalid || saver.saving()">
                {{ "chat.prompts.take" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .prompt-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(480px, 70vw);
                margin-top: 8px;
            }

            mat-form-field {
                width: 100%;
            }

            .software,
            .note,
            .muted {
                color: var(--mat-sys-on-surface-variant);
                font-size: 13px;
            }

            .description {
                margin: 0 0 8px;
            }
        `,
    ],
})
export class ChatPromptDialogComponent {
    private dialogRef = inject<MatDialogRef<ChatPromptDialogComponent, Record<string, string>>>(MatDialogRef);
    /** Takes the text into the input field and closes only when the software gave it. */
    readonly saver = dialogSave<Record<string, string>>();
    readonly prompt = inject<SoftwarePrompt>(MAT_DIALOG_DATA);
    readonly label = softwarePromptLabel(this.prompt);

    readonly form = new FormGroup<Record<string, FormControl<string>>>(
        Object.fromEntries(
            this.prompt.arguments.map(argument => [
                argument.name,
                new FormControl("", {
                    nonNullable: true,
                    validators: argument.required ? [Validators.required, notBlank()] : [],
                }),
            ]),
        ),
    );

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.saver.submit(softwarePromptValues(this.prompt, this.form.getRawValue()));
    }
}
