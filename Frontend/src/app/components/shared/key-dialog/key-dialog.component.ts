import {Component, computed, inject} from "@angular/core";
import {toSignal} from "@angular/core/rxjs-interop";
import {MatCheckboxModule} from "@angular/material/checkbox";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatSelectModule} from "@angular/material/select";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {TranslocoPipe} from "@jsverse/transloco";
import {
    KeyChoices,
    KeyRequest,
    keyRequest,
    KeyRights,
    MAX_KEY_DESCRIPTION,
    Named,
    noDoor,
} from "../../../utils/key-form.util";
import {notBlank} from "../../../utils/validators.util";
import {dialogSave} from "../dialog-save";

/** A software to choose, with the label it is shown with (an MCP key's "Name (nur Antworten)"). */
export interface KeySoftwareChoice {
    id: number;
    label: string;
    /** Only an MCP key: it goes directly to MCP clients - the key may change in it too. */
    changeable?: boolean;
}

export interface KeyDialogData {
    /** "api": a key of the client software (/api/v1), "mcp": an MCP key (/mcp). */
    kind: "api" | "mcp";
    /** The key to change; null: a new one. */
    key:
        | (KeyRights & {
              name: string;
              keepsHistory?: boolean;
              description?: string | null;
              mcp?: boolean;
              a2a?: boolean;
          })
        | null;
    choices: KeyChoices;
    areas: Named[];
    software: KeySoftwareChoice[];
    tasks: Named[];
}

/**
 * Creates or changes a key with everything it may use, one field under the other (DialogSave: it stays open on a
 * refusal). Shows only what the tenant has; what it does not show is not sent (utils/key-form.util.ts).
 */
@Component({
    selector: "app-key-dialog",
    imports: [
        MatCheckboxModule,
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        MatSlideToggleModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>
            {{
                "keyDialog." + data.kind + (data.key ? ".editTitle" : ".createTitle")
                    | transloco: {name: data.key?.name}
            }}
        </h2>
        <mat-dialog-content>
            <form id="key-form" [formGroup]="form" class="key-form" (ngSubmit)="save()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "keyDialog.name" | transloco }}</mat-label>
                    <input
                        matInput
                        formControlName="name"
                        cdkFocusInitial
                        [placeholder]="'keyDialog.' + data.kind + '.namePlaceholder' | transloco"
                    />
                    @if (form.controls.name.invalid && form.controls.name.touched) {
                        <mat-error>{{ "keyDialog.nameRequired" | transloco }}</mat-error>
                    }
                </mat-form-field>

                @if (data.choices.doors) {
                    <div>
                        <mat-checkbox formControlName="mcp">{{ "keyDialog.doorMcp" | transloco }}</mat-checkbox>
                        <mat-checkbox formControlName="a2a">{{ "keyDialog.doorA2a" | transloco }}</mat-checkbox>
                        @if (missingDoor()) {
                            <mat-error>{{ "keyDialog.noDoor" | transloco }}</mat-error>
                        }
                    </div>
                }
                @if (data.choices.description) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "keyDialog.description" | transloco }}</mat-label>
                        <textarea matInput formControlName="description" rows="3"></textarea>
                        <mat-hint>{{ "keyDialog.descriptionHint" | transloco }}</mat-hint>
                        @if (form.controls.description.invalid) {
                            <mat-error>{{ "keyDialog.descriptionTooLong" | transloco }}</mat-error>
                        }
                    </mat-form-field>
                }

                @if (data.choices.areas) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "keyDialog.areas" | transloco }}</mat-label>
                        <mat-select
                            formControlName="areaIds"
                            multiple
                            [placeholder]="'keyDialog.onlyCompanyKnowledge' | transloco"
                        >
                            @for (area of data.areas; track area.id) {
                                <mat-option [value]="area.id">{{ area.name }}</mat-option>
                            }
                        </mat-select>
                        <mat-hint>{{ "keyDialog." + data.kind + ".areasHint" | transloco }}</mat-hint>
                    </mat-form-field>
                }

                @if (data.choices.software) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "keyDialog.software" | transloco }}</mat-label>
                        <mat-select
                            formControlName="connectionIds"
                            multiple
                            [placeholder]="'keyDialog.noSoftware' | transloco"
                        >
                            @for (software of data.software; track software.id) {
                                <mat-option [value]="software.id">{{ software.label }}</mat-option>
                            }
                        </mat-select>
                        <mat-hint>{{ "keyDialog." + data.kind + ".softwareHint" | transloco }}</mat-hint>
                    </mat-form-field>
                    @if (data.choices.changing && changeableSoftware().length > 0) {
                        <mat-form-field appearance="outline">
                            <mat-label>{{ "keyDialog.changing" | transloco }}</mat-label>
                            <mat-select
                                formControlName="changingConnectionIds"
                                multiple
                                [placeholder]="'keyDialog.noChanging' | transloco"
                            >
                                @for (software of changeableSoftware(); track software.id) {
                                    <mat-option [value]="software.id">{{ software.label }}</mat-option>
                                }
                            </mat-select>
                            <mat-hint>{{ "keyDialog.changingHint" | transloco }}</mat-hint>
                        </mat-form-field>
                    }
                }

                @if (data.choices.tasks) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "keyDialog.tasks" | transloco }}</mat-label>
                        <mat-select formControlName="taskIds" multiple [placeholder]="'keyDialog.noTasks' | transloco">
                            @for (task of data.tasks; track task.id) {
                                <mat-option [value]="task.id">{{ task.name }}</mat-option>
                            }
                        </mat-select>
                        <mat-hint>{{ "keyDialog." + data.kind + ".tasksHint" | transloco }}</mat-hint>
                    </mat-form-field>
                }

                @if (data.choices.webSearch) {
                    <div class="toggle">
                        <mat-slide-toggle formControlName="webSearch">{{
                            "keyDialog.webSearch" | transloco
                        }}</mat-slide-toggle>
                        <div class="toggle-hint">{{ "keyDialog.webSearchHint" | transloco }}</div>
                    </div>
                }

                @if (data.choices.keepsHistory) {
                    <div class="toggle">
                        <mat-slide-toggle formControlName="keepsHistory">{{
                            "keyDialog.keepsHistory" | transloco
                        }}</mat-slide-toggle>
                        <div class="toggle-hint">{{ "keyDialog.keepsHistoryHint" | transloco }}</div>
                    </div>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="cancel()">{{ "common.cancel" | transloco }}</button>
            <!-- Submit button of the form above (via form=) so Enter in a field saves too. -->
            <button
                mat-flat-button
                type="submit"
                form="key-form"
                [disabled]="form.invalid || missingDoor() || saver.saving()"
            >
                {{ (data.key ? "common.save" : "keyDialog.create") | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .key-form {
                display: flex;
                flex-direction: column;
                gap: 12px;
                min-width: min(460px, 75vw);
                margin-top: 16px;
            }

            mat-form-field {
                width: 100%;
            }

            .toggle-hint {
                color: var(--app-text-2);
                font-size: 12px;
                margin: 4px 0 0 52px;
            }
        `,
    ],
})
export class KeyDialogComponent {
    readonly data = inject<KeyDialogData>(MAT_DIALOG_DATA);
    readonly saver = dialogSave<KeyRequest>();
    private dialogRef = inject(MatDialogRef<KeyDialogComponent>);
    private fb = inject(FormBuilder);

    readonly form = this.fb.nonNullable.group({
        name: [this.data.key?.name ?? "", [Validators.required, notBlank()]],
        areaIds: [this.data.key?.areaIds ?? ([] as number[])],
        connectionIds: [this.data.key?.connectionIds ?? ([] as number[])],
        // None unless chosen: a key reads only, as the backend's default.
        changingConnectionIds: [this.data.key?.changingConnectionIds ?? ([] as number[])],
        taskIds: [this.data.key?.taskIds ?? ([] as number[])],
        webSearch: [this.data.key?.webSearch ?? false],
        // A new API key keeps its questions unless told not to - as the backend does when it is left out.
        keepsHistory: [this.data.key?.keepsHistory ?? true],
        description: [this.data.key?.description ?? "", Validators.maxLength(MAX_KEY_DESCRIPTION)],
        // A new MCP key opens /mcp, as the backend's default.
        mcp: [this.data.key?.mcp ?? true],
        a2a: [this.data.key?.a2a ?? false],
    });
    private readonly values = toSignal(this.form.valueChanges, {initialValue: this.form.getRawValue()});
    readonly missingDoor = computed(() =>
        noDoor({mcp: this.values().mcp ?? false, a2a: this.values().a2a ?? false}, this.data.choices),
    );
    private readonly changeable = this.data.software
        .filter(software => software.changeable)
        .map(software => software.id);
    /** What the key may change in: of the software chosen above, those that go directly to MCP clients. */
    readonly changeableSoftware = computed(() => {
        const used = this.values().connectionIds ?? [];
        return this.data.software.filter(software => software.changeable && used.includes(software.id));
    });

    save(): void {
        if (this.form.invalid || this.missingDoor()) {
            return;
        }
        this.saver.submit(keyRequest(this.form.getRawValue(), this.data.choices, this.changeable));
    }

    cancel(): void {
        this.dialogRef.close();
    }
}
