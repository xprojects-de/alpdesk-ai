import {Component, computed, inject} from "@angular/core";
import {toSignal} from "@angular/core/rxjs-interop";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MatButtonModule} from "@angular/material/button";
import {MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatIconModule} from "@angular/material/icon";
import {MatInputModule} from "@angular/material/input";
import {MatSelectModule} from "@angular/material/select";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {MatTooltipModule} from "@angular/material/tooltip";
import {TranslocoPipe} from "@jsverse/transloco";
import {FileStore} from "../../../models/file-store.model";
import {KnowledgeArea} from "../../../models/knowledge-area.model";
import {KnowledgeSource, KnowledgeSourceRequest, SOURCE_SCHEDULES} from "../../../models/knowledge-source.model";
import {TenantScope} from "../../../models/tenant.model";
import {COMPANY_KNOWLEDGE_KEY} from "../../../utils/knowledge-area.util";
import {buildSourceRequest, overlappingSource, readsOtherFiles} from "../../../utils/knowledge-source.util";
import {notBlank} from "../../../utils/validators.util";
import {dialogSave} from "../dialog-save";
import {FolderPickerData, FolderPickerDialogComponent} from "./folder-picker-dialog.component";

export interface KnowledgeSourceDialogData {
    scope: TenantScope;
    source: KnowledgeSource | null;
    /** The tenant's sources - one on the same folder, above or below it, is named. */
    sources: KnowledgeSource[];
    stores: FileStore[];
    areas: KnowledgeArea[];
}

/** A store a source may read: its server confirmed (a fingerprint, or a check that logged in). */
function ready(store: FileStore): boolean {
    return store.fingerprint !== null || (store.checkedAt !== null && store.checkError === null);
}

/** Create/edit dialog of a knowledge source. */
@Component({
    selector: "app-knowledge-source-dialog",
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatSlideToggleModule,
        MatTooltipModule,
        MatButtonModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>
            {{ (source ? "knowledgeSources.dialog.titleEdit" : "knowledgeSources.panel.add") | transloco }}
        </h2>
        <mat-dialog-content>
            <form id="knowledge-source-form" [formGroup]="form" class="source-form" (ngSubmit)="onSave()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "common.name" | transloco }}</mat-label>
                    <input matInput formControlName="name" required maxlength="80" cdkFocusInitial />
                    <mat-hint>{{ "knowledgeSources.dialog.nameHint" | transloco }}</mat-hint>
                    @if (form.controls.name.hasError("required") && form.controls.name.touched) {
                        <mat-error>{{ "knowledgeSources.dialog.nameRequired" | transloco }}</mat-error>
                    }
                    @if (form.controls.name.hasError("blank")) {
                        <mat-error>{{ "knowledgeSources.dialog.nameBlank" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "knowledgeSources.dialog.store" | transloco }}</mat-label>
                    <mat-select formControlName="fileStoreId" required>
                        @for (store of data.stores; track store.id) {
                            <mat-option [value]="store.id" [disabled]="!isReady(store)">
                                {{ store.name }} ({{ store.basePath }})
                                @if (!isReady(store)) {
                                    - {{ "knowledgeSources.dialog.storeNotReady" | transloco }}
                                }
                            </mat-option>
                        }
                    </mat-select>
                    <mat-hint>{{ "knowledgeSources.dialog.storeHint" | transloco }}</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "knowledgeSources.dialog.folder" | transloco }}</mat-label>
                    <input
                        matInput
                        formControlName="folder"
                        [placeholder]="'knowledgeSources.dialog.folderPlaceholder' | transloco"
                    />
                    <button
                        mat-icon-button
                        matSuffix
                        type="button"
                        (click)="chooseFolder()"
                        [disabled]="!chosenStore()"
                        [matTooltip]="'knowledgeSources.dialog.chooseFolder' | transloco"
                        [attr.aria-label]="'knowledgeSources.dialog.chooseFolder' | transloco"
                    >
                        <mat-icon>folder_open</mat-icon>
                    </button>
                    <mat-hint>{{ "knowledgeSources.dialog.folderHint" | transloco }}</mat-hint>
                </mat-form-field>
                @if (overlap(); as other) {
                    <p class="app-note warn">
                        <mat-icon>content_copy</mat-icon>
                        <span>{{ "knowledgeSources.dialog.overlap" | transloco: {name: other.name} }}</span>
                    </p>
                }
                <!-- The company knowledge is the empty choice (null) - shown as the placeholder, the label above it. -->
                <mat-form-field appearance="outline" [floatLabel]="labelAbove">
                    <mat-label>{{ "knowledgeSources.dialog.area" | transloco }}</mat-label>
                    <mat-select formControlName="areaId" [placeholder]="companyKnowledge | transloco">
                        <mat-option [value]="null">{{ companyKnowledge | transloco }}</mat-option>
                        @for (area of data.areas; track area.id) {
                            <mat-option [value]="area.id">{{ area.name }}</mat-option>
                        }
                    </mat-select>
                    <mat-hint>{{ "knowledgeSources.dialog.areaHint" | transloco }}</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "knowledgeSources.dialog.pattern" | transloco }}</mat-label>
                    <input matInput formControlName="pattern" maxlength="200" [placeholder]="patternExample" />
                    <mat-hint>{{ "knowledgeSources.dialog.patternHint" | transloco }}</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "knowledgeSources.dialog.schedule" | transloco }}</mat-label>
                    <mat-select formControlName="schedule">
                        @for (schedule of schedules; track schedule) {
                            <mat-option [value]="schedule">{{
                                "knowledgeSources.schedule." + schedule | transloco
                            }}</mat-option>
                        }
                    </mat-select>
                    <mat-hint>{{ "knowledgeSources.dialog.scheduleHint" | transloco }}</mat-hint>
                </mat-form-field>
                <mat-slide-toggle formControlName="active">{{
                    "knowledgeSources.dialog.active" | transloco
                }}</mat-slide-toggle>
                @if (readsOther()) {
                    <p class="app-note warn">
                        <mat-icon>warning</mat-icon>
                        <span>{{ "knowledgeSources.dialog.otherFiles" | transloco }}</span>
                    </p>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button
                mat-flat-button
                type="submit"
                form="knowledge-source-form"
                [disabled]="form.invalid || saver.saving()"
            >
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .source-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(520px, 70vw);
                margin-top: 16px;
            }

            mat-form-field {
                width: 100%;
            }

            mat-slide-toggle {
                margin: 4px 0 8px;
            }

            .app-note {
                margin: 8px 0 0;
            }
        `,
    ],
})
export class KnowledgeSourceDialogComponent {
    private fb = inject(FormBuilder);
    private dialog = inject(MatDialog);
    private dialogRef = inject<MatDialogRef<KnowledgeSourceDialogComponent, KnowledgeSourceRequest>>(MatDialogRef);
    /** Saves and closes only when saved - a folder refused by the backend keeps everything entered. */
    readonly saver = dialogSave<KnowledgeSourceRequest>();
    readonly data = inject<KnowledgeSourceDialogData>(MAT_DIALOG_DATA);
    readonly source = this.data.source;
    readonly schedules = SOURCE_SCHEDULES;
    readonly companyKnowledge = COMPANY_KNOWLEDGE_KEY;
    readonly isReady = ready;
    /** The area's label stays above the field, so the company knowledge shows as its placeholder. */
    readonly labelAbove = "always" as const;
    /** A file name pattern is the same in every language. */
    readonly patternExample = "*.pdf";

    form = this.fb.nonNullable.group({
        name: [this.source?.name ?? "", [Validators.required, notBlank(), Validators.maxLength(80)]],
        fileStoreId: this.fb.control<number | null>(
            this.source?.fileStoreId ?? this.data.stores.find(ready)?.id ?? null,
            Validators.required,
        ),
        folder: [this.source?.folder ?? ""],
        areaId: this.fb.control<number | null>(this.source?.areaId ?? null),
        pattern: [this.source?.pattern ?? ""],
        schedule: [this.source?.schedule ?? SOURCE_SCHEDULES[0]],
        active: [this.source?.active ?? true],
    });

    private readonly value = toSignal(this.form.valueChanges, {initialValue: this.form.getRawValue()});
    /** Another store or folder: its documents go, and it is read anew - said before it is saved. */
    readonly readsOther = computed(() => {
        const value = this.value();
        return readsOtherFiles(this.source, {fileStoreId: value.fileStoreId ?? null, folder: value.folder ?? ""});
    });

    /** The ready store chosen - the one whose folders can be looked into. */
    readonly chosenStore = computed(() => {
        const store = this.data.stores.find(candidate => candidate.id === this.value().fileStoreId);
        return store !== undefined && ready(store) ? store : null;
    });

    /** Another source of the same store on this folder, above or below it - its documents would stand twice. */
    readonly overlap = computed(() => {
        const value = this.value();
        return overlappingSource(
            this.data.sources,
            this.source?.id ?? null,
            value.fileStoreId ?? null,
            value.folder ?? "",
        );
    });

    /** Looks into the chosen store's folders - from the one typed so far - and takes the one chosen. */
    chooseFolder(): void {
        const store = this.chosenStore();
        if (store === null) {
            return;
        }
        this.dialog
            .open<FolderPickerDialogComponent, FolderPickerData, string>(FolderPickerDialogComponent, {
                width: "600px",
                data: {scope: this.data.scope, store, path: this.form.controls.folder.value.trim()},
            })
            .afterClosed()
            .subscribe(path => {
                if (path !== undefined) {
                    this.form.controls.folder.setValue(path);
                }
            });
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        this.saver.submit(buildSourceRequest(this.form.getRawValue()));
    }
}
