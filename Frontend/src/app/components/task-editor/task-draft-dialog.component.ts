import {Component, computed, effect, inject, OnInit} from "@angular/core";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {MatButtonModule} from "@angular/material/button";
import {MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSelectModule} from "@angular/material/select";
import {TaskDraftRequest} from "../../models/task.model";
import {TenantScope} from "../../models/tenant.model";
import * as TaskActions from "../../store/task/task.actions";
import * as TaskSelectors from "../../store/task/task.selectors";
import {dialogSave} from "../shared/dialog-save";
import {draftRequest, MAX_DRAFT_DESCRIPTION} from "../../utils/task.util";

export interface TaskDraftDialogData {
    scope: TenantScope;
    /** The editor's text when a task is changed; null: a new one. */
    source: string | null;
}

/**
 * "Draft with AI": the tenant admin describes the task - or the change - in their own words, the tenant's chat model
 * writes it and the check corrects it. Stays open while the model works (it may take minutes) and when it fails.
 */
@Component({
    selector: "app-task-draft-dialog",
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatProgressBarModule,
        MatSelectModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>{{ "tasks.draft.title" | transloco }}</h2>
        <mat-dialog-content>
            <p class="intro">
                {{ (data.source ? "tasks.draft.introChange" : "tasks.draft.introNew") | transloco }}
            </p>
            <form [formGroup]="form" id="task-draft-form" (ngSubmit)="submit()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "tasks.draft.description" | transloco }}</mat-label>
                    <textarea
                        matInput
                        formControlName="description"
                        rows="6"
                        [maxlength]="maxLength"
                        [placeholder]="'tasks.draft.placeholder' | transloco"
                    ></textarea>
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "tasks.draft.model" | transloco }}</mat-label>
                    <mat-select formControlName="modelId">
                        @for (model of models(); track model.id) {
                            <mat-option [value]="model.id">{{ model.displayName }}</mat-option>
                        }
                    </mat-select>
                </mat-form-field>
            </form>
            @if (saver.saving()) {
                <p class="muted">{{ "tasks.draft.working" | transloco }}</p>
                <mat-progress-bar mode="indeterminate" />
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close [disabled]="saver.saving()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="task-draft-form" [disabled]="form.invalid || saver.saving()">
                {{ "tasks.draft.write" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            form {
                display: flex;
                flex-direction: column;
                gap: 8px;
            }

            .intro,
            .muted {
                color: var(--app-text-2);
            }
        `,
    ],
})
export class TaskDraftDialogComponent implements OnInit {
    private store = inject(Store);
    private fb = inject(FormBuilder);
    readonly data = inject<TaskDraftDialogData>(MAT_DIALOG_DATA);
    readonly saver = dialogSave<TaskDraftRequest>();
    readonly maxLength = MAX_DRAFT_DESCRIPTION;
    private readonly draftModels = this.store.selectSignal(TaskSelectors.selectDraftModels);
    readonly models = computed(() => this.draftModels()?.models ?? []);

    readonly form = this.fb.nonNullable.group({
        description: ["", [Validators.required, Validators.maxLength(MAX_DRAFT_DESCRIPTION)]],
        modelId: [null as number | null],
    });

    constructor() {
        // The default model preselected once the models are there.
        effect(() => {
            const models = this.draftModels();
            if (models && this.form.controls.modelId.value === null) {
                this.form.controls.modelId.setValue(models.defaultModelId);
            }
        });
    }

    ngOnInit(): void {
        this.store.dispatch(TaskActions.loadDraftModels({scope: this.data.scope}));
    }

    submit(): void {
        if (this.form.invalid) {
            return;
        }
        const {description, modelId} = this.form.getRawValue();
        this.saver.submit(draftRequest(description, this.data.source, modelId));
    }
}
