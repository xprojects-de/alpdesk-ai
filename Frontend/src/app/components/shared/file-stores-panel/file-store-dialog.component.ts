import {Component, computed, inject} from "@angular/core";
import {takeUntilDestroyed, toSignal} from "@angular/core/rxjs-interop";
import {NonNullableFormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MatButtonModule} from "@angular/material/button";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatSelectModule} from "@angular/material/select";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {TranslocoPipe} from "@jsverse/transloco";
import {FILE_STORE_TYPES, FileStore, FileStoreRequest} from "../../../models/file-store.model";
import {
    buildFileStoreRequest,
    fieldTexts,
    FileStoreFormValue,
    passwordNeeded,
    requiredFields,
} from "../../../utils/file-store.util";
import {dialogSave} from "../dialog-save";
import {createFileStoreForm} from "./file-store-form";
import {S3StoreFieldsComponent} from "./s3-store-fields.component";
import {ServerStoreFieldsComponent} from "./server-store-fields.component";
import {SharePointStoreFieldsComponent} from "./sharepoint-store-fields.component";
import {MailboxStoreFieldsComponent} from "./mailbox-store-fields.component";

/**
 * Create/edit dialog of a file store: opened with the store to edit, or null. The fields of each type are a component
 * of their own; name, type, the secret and the folder are shared.
 */
@Component({
    selector: "app-file-store-dialog",
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        MatSlideToggleModule,
        MatButtonModule,
        TranslocoPipe,
        ServerStoreFieldsComponent,
        S3StoreFieldsComponent,
        SharePointStoreFieldsComponent,
        MailboxStoreFieldsComponent,
    ],
    template: `
        <h2 mat-dialog-title>
            {{ (store ? "fileStores.dialog.titleEdit" : "fileStores.panel.add") | transloco }}
        </h2>
        <mat-dialog-content>
            <form id="file-store-form" [formGroup]="form" class="store-form" (ngSubmit)="onSave()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "common.name" | transloco }}</mat-label>
                    <input matInput formControlName="name" required maxlength="80" cdkFocusInitial />
                    <mat-hint>{{ "fileStores.dialog.nameHint" | transloco }}</mat-hint>
                    @if (form.controls.name.hasError("required") && form.controls.name.touched) {
                        <mat-error>{{ "fileStores.dialog.nameRequired" | transloco }}</mat-error>
                    }
                    @if (form.controls.name.hasError("blank")) {
                        <mat-error>{{ "fileStores.dialog.nameBlank" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "fileStores.dialog.type" | transloco }}</mat-label>
                    <mat-select formControlName="type">
                        @for (type of types; track type) {
                            <mat-option [value]="type">{{ "fileStores.type." + type | transloco }}</mat-option>
                        }
                    </mat-select>
                    <mat-hint>{{ "fileStores.dialog.typeHint." + form.controls.type.value | transloco }}</mat-hint>
                </mat-form-field>
                @switch (form.controls.type.value) {
                    @case ("S3") {
                        <app-s3-store-fields [form]="form" />
                    }
                    @case ("SHAREPOINT") {
                        <app-sharepoint-store-fields [form]="form" />
                    }
                    @case ("IMAP") {
                        <app-mailbox-store-fields [form]="form" />
                    }
                    @default {
                        <app-server-store-fields [form]="form" />
                    }
                }
                <mat-form-field appearance="outline">
                    <mat-label>{{ texts().secret | transloco }}</mat-label>
                    <input matInput type="password" formControlName="password" autocomplete="new-password" />
                    <mat-hint>
                        {{
                            (needsPassword()
                                ? store
                                    ? texts().secretAgain
                                    : texts().secretHint
                                : "fileStores.dialog.passwordStored"
                            ) | transloco
                        }}
                    </mat-hint>
                    @if (form.controls.password.hasError("required") && form.controls.password.touched) {
                        <mat-error>{{ "fileStores.dialog.passwordRequired" | transloco }}</mat-error>
                    }
                </mat-form-field>
                @if (form.controls.type.value !== "IMAP") {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "fileStores.dialog.basePath" | transloco }}</mat-label>
                        <input matInput formControlName="basePath" placeholder="/" />
                        <mat-hint>{{ texts().folderHint | transloco }}</mat-hint>
                    </mat-form-field>
                    <mat-slide-toggle formControlName="taskWrite">{{
                        "fileStores.dialog.taskWrite" | transloco
                    }}</mat-slide-toggle>
                    <p class="toggle-hint">{{ "fileStores.dialog.taskWriteHint" | transloco }}</p>
                } @else {
                    <p class="toggle-hint">{{ "fileStores.dialog.mailboxReadOnly" | transloco }}</p>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="file-store-form" [disabled]="form.invalid || saver.saving()">
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .store-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(520px, 70vw);
                margin-top: 16px;
            }

            mat-form-field {
                width: 100%;
            }

            .toggle-hint {
                margin: 0;
                font-size: 12px;
                color: var(--app-text-2);
            }
        `,
    ],
})
export class FileStoreDialogComponent {
    private dialogRef = inject<MatDialogRef<FileStoreDialogComponent, FileStoreRequest>>(MatDialogRef);
    /** Saves and closes only when saved - a host refused by the backend keeps everything entered. */
    readonly saver = dialogSave<FileStoreRequest>();
    readonly store = inject<FileStore | null>(MAT_DIALOG_DATA);
    readonly types = FILE_STORE_TYPES;
    readonly form = createFileStoreForm(inject(NonNullableFormBuilder), this.store);
    private readonly type = toSignal(this.form.controls.type.valueChanges, {
        initialValue: this.form.controls.type.value,
    });
    /** How the chosen type names its secret and folder. */
    readonly texts = computed(() => fieldTexts(this.type()));

    constructor() {
        this.updateRules();
        this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.updateRules());
    }

    needsPassword(): boolean {
        return passwordNeeded(this.store, this.form.getRawValue());
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        this.saver.submit(buildFileStoreRequest(this.form.getRawValue()));
    }

    /**
     * Each type requires its own fields; a new store and another server need the password - the stored one is not sent
     * to another server.
     */
    private updateRules(): void {
        const value = this.form.getRawValue();
        const required = new Set<keyof FileStoreFormValue>(requiredFields(value.type, value.endpoint));
        if (this.needsPassword()) {
            required.add("password");
        }
        for (const field of RULED_FIELDS) {
            const control = this.form.controls[field];
            if (required.has(field) !== control.hasValidator(Validators.required)) {
                if (required.has(field)) {
                    control.addValidators(Validators.required);
                } else {
                    control.removeValidators(Validators.required);
                }
                control.updateValueAndValidity({emitEvent: false});
            }
        }
    }
}

/** The fields whose being required depends on the type or on the store as it was. */
const RULED_FIELDS = [
    "host",
    "username",
    "bucket",
    "accessKeyId",
    "region",
    "directoryId",
    "clientId",
    "siteUrl",
    "password",
] as const;
