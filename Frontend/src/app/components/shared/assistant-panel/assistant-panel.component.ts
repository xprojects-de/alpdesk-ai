import {Component, computed, DestroyRef, effect, inject, input, OnInit} from "@angular/core";
import {takeUntilDestroyed, toSignal} from "@angular/core/rxjs-interop";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {ActionCreator, Creator, Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {filter} from "rxjs/operators";
import {MatButtonModule} from "@angular/material/button";
import {MatDialog} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatIconModule} from "@angular/material/icon";
import {MatInputModule} from "@angular/material/input";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSelectModule} from "@angular/material/select";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTooltipModule} from "@angular/material/tooltip";
import {
    ANSWER_SOURCE_LABELS,
    ANSWER_SOURCES,
    ChatTemplate,
    ChatTemplateRequest,
    CREATIVITIES,
    CREATIVITY_LABELS,
    MAX_INSTRUCTIONS,
    MAX_SEARCH_RESULTS,
    MIN_SEARCH_RESULTS,
    REASONING_LABELS,
    REASONINGS,
} from "../../../models/assistant.model";
import {TenantScope} from "../../../models/tenant.model";
import * as AssistantActions from "../../../store/assistant/assistant.actions";
import * as AssistantSelectors from "../../../store/assistant/assistant.selectors";
import {
    buildAssistantSettings,
    isSearchResults,
    imageModelHint,
    noImageModelLabel,
    SummaryPart,
    templateSummary,
} from "../../../utils/assistant.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {DialogSave, DialogSaveSpec} from "../dialog-save";
import {ChatTemplateDialogComponent} from "./chat-template-dialog.component";

/**
 * How a tenant's assistant answers: its instructions (sent with every question) and defaults, and the templates its
 * users choose in the chat - the tenant admin's page "Assistent" or the admin's dialog of the same name.
 */
@Component({
    selector: "app-assistant-panel",
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressBarModule,
        MatSelectModule,
        MatTooltipModule,
        EmptyStateComponent,
        TranslocoPipe,
    ],
    template: `
        <form class="app-panel" [formGroup]="form" (ngSubmit)="onSaveSettings()">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "assistant.settings.title" | transloco }}</span>
                <span class="muted">{{ "assistant.settings.intro" | transloco }}</span>
            </div>
            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }
            <div class="app-panel-body settings">
                <mat-form-field appearance="outline" class="wide">
                    <mat-label>{{ "assistant.settings.instructions" | transloco }}</mat-label>
                    <textarea matInput formControlName="instructions" rows="7"></textarea>
                    <mat-hint>
                        {{
                            "assistant.settings.instructionsHint"
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
                            @for (source of answerSources; track source) {
                                <mat-option [value]="source">{{ answerSourceLabels[source] | transloco }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "assistant.form.creativity" | transloco }}</mat-label>
                        <mat-select formControlName="creativity">
                            @for (creativity of creativities; track creativity) {
                                <mat-option [value]="creativity">{{
                                    creativityLabels[creativity] | transloco
                                }}</mat-option>
                            }
                        </mat-select>
                        <mat-hint>{{ "assistant.settings.creativityHint" | transloco }}</mat-hint>
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "assistant.form.reasoning" | transloco }}</mat-label>
                        <mat-select formControlName="reasoning">
                            @for (reasoning of reasonings; track reasoning) {
                                <mat-option [value]="reasoning">{{
                                    reasoningLabels[reasoning] | transloco
                                }}</mat-option>
                            }
                        </mat-select>
                        <mat-hint>{{ "assistant.settings.reasoningHint" | transloco }}</mat-hint>
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "assistant.form.searchResults" | transloco }}</mat-label>
                        <input
                            matInput
                            type="number"
                            formControlName="searchResults"
                            [min]="minResults"
                            [max]="maxResults"
                        />
                        <mat-hint>{{
                            "assistant.settings.searchResultsHint" | transloco: {min: minResults, max: maxResults}
                        }}</mat-hint>
                        @if (form.controls.searchResults.invalid) {
                            <mat-error>{{
                                "assistant.form.range" | transloco: {min: minResults, max: maxResults}
                            }}</mat-error>
                        }
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "assistant.settings.defaultModel" | transloco }}</mat-label>
                        <mat-select formControlName="defaultModelId">
                            <mat-option [value]="null">{{ "assistant.settings.none" | transloco }}</mat-option>
                            @for (model of models(); track model.id) {
                                <mat-option [value]="model.id">{{ model.displayName }}</mat-option>
                            }
                        </mat-select>
                        <mat-hint>{{ "assistant.settings.defaultModelHint" | transloco }}</mat-hint>
                    </mat-form-field>
                    @if (imageModels().length > 0) {
                        <mat-form-field appearance="outline">
                            <mat-label>{{ "assistant.settings.imageModel" | transloco }}</mat-label>
                            <mat-select formControlName="imageModelId">
                                <mat-option [value]="null">{{
                                    noImageModelLabel().key | transloco: noImageModelLabel().params
                                }}</mat-option>
                                @for (model of imageModels(); track model.id) {
                                    <mat-option [value]="model.id">{{ model.displayName }}</mat-option>
                                }
                            </mat-select>
                            <mat-hint>{{ imageModelHint().key | transloco: imageModelHint().params }}</mat-hint>
                        </mat-form-field>
                    }
                </div>
                <div class="actions">
                    <button mat-flat-button type="submit" [disabled]="form.invalid || saving() || !settings()">
                        {{ "common.save" | transloco }}
                    </button>
                </div>
            </div>
        </form>

        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "assistant.templates.title" | transloco }}</span>
                <span class="muted">{{ "assistant.templates.intro" | transloco }}</span>
                <span class="spacer"></span>
                <button mat-stroked-button (click)="openCreateDialog()" [disabled]="!settings()">
                    <mat-icon>add</mat-icon>
                    {{ "assistant.templates.add" | transloco }}
                </button>
            </div>
            @if (templates().length > 0) {
                <p class="hint">{{ "assistant.templates.hint" | transloco }}</p>
                @for (template of templates(); track template.id) {
                    <div class="template">
                        <div class="template-text">
                            <div class="template-name">{{ template.name }}</div>
                            <div class="template-summary">
                                @for (part of summary(template); track $index) {
                                    {{ $first ? "" : " · " }}
                                    @if (part.valueKey) {
                                        {{ part.key | transloco: {value: part.valueKey | transloco} }}
                                    } @else {
                                        {{ part.key | transloco: part.params }}
                                    }
                                }
                            </div>
                            @if (template.instructions) {
                                <div class="template-instructions">{{ template.instructions }}</div>
                            }
                        </div>
                        <button
                            mat-icon-button
                            (click)="openEditDialog(template)"
                            [matTooltip]="'common.edit' | transloco"
                        >
                            <mat-icon>edit</mat-icon>
                        </button>
                        <button
                            mat-icon-button
                            class="danger"
                            (click)="confirmDelete(template)"
                            [matTooltip]="'common.delete' | transloco"
                        >
                            <mat-icon>delete</mat-icon>
                        </button>
                    </div>
                }
            } @else if (!loading()) {
                <app-empty-state
                    icon="bookmarks"
                    [title]="'assistant.templates.emptyTitle' | transloco"
                    [text]="'assistant.templates.emptyText' | transloco"
                >
                    <button mat-flat-button (click)="openCreateDialog()" [disabled]="!settings()">
                        <mat-icon>add</mat-icon>
                        {{ "assistant.templates.add" | transloco }}
                    </button>
                </app-empty-state>
            }
        </div>
    `,
    styles: [
        `
            :host {
                display: flex;
                flex-direction: column;
                gap: 16px;
            }

            .settings {
                display: flex;
                flex-direction: column;
                gap: 8px;
            }

            .row {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
                gap: 12px;
            }

            mat-form-field {
                width: 100%;
            }

            .actions {
                display: flex;
                justify-content: flex-end;
            }

            .hint {
                color: var(--app-text-2);
                font-size: 13px;
                margin: 0;
                padding: 12px 14px;
                border-bottom: 1px solid var(--app-border);
            }

            .template {
                display: flex;
                align-items: flex-start;
                gap: 4px;
                padding: 12px 8px 12px 14px;
                border-bottom: 1px solid var(--app-border);
            }

            .template:last-child {
                border-bottom: none;
            }

            .template-text {
                flex: 1;
                min-width: 0;
            }

            .template-name {
                font-weight: 500;
            }

            .template-summary {
                font-size: 12px;
                color: var(--app-text-3);
            }

            .template-instructions {
                font-size: 13px;
                color: var(--app-text-2);
                margin-top: 4px;
                white-space: pre-line;
                overflow: hidden;
                display: -webkit-box;
                -webkit-line-clamp: 3;
                -webkit-box-orient: vertical;
            }

            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class AssistantPanelComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private fb = inject(FormBuilder);
    private transloco = inject(TranslocoService);

    readonly scope = input.required<TenantScope>();

    readonly settings = this.store.selectSignal(AssistantSelectors.selectSettings);
    readonly templates = this.store.selectSignal(AssistantSelectors.selectTemplates);
    readonly loading = this.store.selectSignal(AssistantSelectors.selectAssistantLoading);
    readonly saving = this.store.selectSignal(AssistantSelectors.selectSavingSettings);
    readonly models = this.store.selectSignal(AssistantSelectors.selectModels);
    readonly imageModels = this.store.selectSignal(AssistantSelectors.selectImageModels);
    readonly noImageModelLabel = computed(() => noImageModelLabel(this.imageModels()));

    readonly answerSources = ANSWER_SOURCES;
    readonly creativities = CREATIVITIES;
    readonly reasonings = REASONINGS;
    readonly answerSourceLabels = ANSWER_SOURCE_LABELS;
    readonly creativityLabels = CREATIVITY_LABELS;
    readonly reasoningLabels = REASONING_LABELS;
    readonly maxInstructions = MAX_INSTRUCTIONS;
    readonly minResults = MIN_SEARCH_RESULTS;
    readonly maxResults = MAX_SEARCH_RESULTS;

    form = this.fb.nonNullable.group({
        instructions: ["", [Validators.maxLength(MAX_INSTRUCTIONS)]],
        answerSource: this.fb.nonNullable.control<"DOCUMENTS_ONLY" | "DOCUMENTS_FIRST">("DOCUMENTS_ONLY"),
        creativity: this.fb.nonNullable.control<"LOW" | "MEDIUM" | "HIGH">("MEDIUM"),
        reasoning: this.fb.nonNullable.control<"OFF" | "LOW" | "MEDIUM" | "HIGH">("LOW"),
        searchResults: this.fb.control<number | null>(5, [
            control => (isSearchResults(control.value) ? null : {range: true}),
        ]),
        defaultModelId: this.fb.control<number | null>(null),
        imageModelId: this.fb.control<number | null>(null),
    });
    private readonly chosenImageModel = toSignal(this.form.controls.imageModelId.valueChanges, {initialValue: null});
    readonly imageModelHint = computed(() => imageModelHint(this.imageModels(), this.chosenImageModel()));

    constructor() {
        // The form shows what is stored - after loading and after saving.
        effect(() => {
            const settings = this.settings();
            if (settings) {
                this.form.setValue({...settings});
            }
        });
        this.notifyOn(AssistantActions.saveSettingsSuccess, () =>
            this.transloco.translate("assistant.snack.settingsSaved"),
        );
        this.notifyOn(
            AssistantActions.saveSettingsFailure,
            ({error}) => this.transloco.translate("assistant.snack.error", {error}),
            true,
        );
        this.notifyOn(AssistantActions.createTemplateSuccess, () =>
            this.transloco.translate("assistant.snack.templateCreated"),
        );
        this.notifyOn(
            AssistantActions.createTemplateFailure,
            ({error}) => this.transloco.translate("assistant.snack.error", {error}),
            true,
        );
        this.notifyOn(AssistantActions.updateTemplateSuccess, () =>
            this.transloco.translate("assistant.snack.templateSaved"),
        );
        this.notifyOn(
            AssistantActions.updateTemplateFailure,
            ({error}) => this.transloco.translate("assistant.snack.error", {error}),
            true,
        );
        this.notifyOn(AssistantActions.deleteTemplateSuccess, () =>
            this.transloco.translate("assistant.snack.templateDeleted"),
        );
        this.notifyOn(
            AssistantActions.deleteTemplateFailure,
            ({error}) => this.transloco.translate("assistant.snack.deleteError", {error}),
            true,
        );
    }

    ngOnInit(): void {
        this.store.dispatch(AssistantActions.loadAssistant({scope: this.scope()}));
    }

    summary(template: ChatTemplate): SummaryPart[] {
        return templateSummary(template);
    }

    onSaveSettings(): void {
        if (this.form.invalid) {
            return;
        }
        this.store.dispatch(
            AssistantActions.saveSettings({
                scope: this.scope(),
                settings: buildAssistantSettings(this.form.getRawValue()),
            }),
        );
    }

    openCreateDialog(): void {
        this.openTemplateDialog(null, {
            action: template => AssistantActions.createTemplate({scope: this.scope(), template}),
            success: [AssistantActions.createTemplateSuccess],
            failure: AssistantActions.createTemplateFailure,
        });
    }

    openEditDialog(existing: ChatTemplate): void {
        this.openTemplateDialog(existing, {
            action: template => AssistantActions.updateTemplate({scope: this.scope(), id: existing.id, template}),
            success: [AssistantActions.updateTemplateSuccess],
            failure: AssistantActions.updateTemplateFailure,
        });
    }

    confirmDelete(template: ChatTemplate): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("assistant.templates.confirmDelete", {name: template.name}),
                    confirmLabel: this.transloco.translate("common.delete"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() =>
                this.store.dispatch(AssistantActions.deleteTemplate({scope: this.scope(), id: template.id})),
            );
    }

    private openTemplateDialog(data: ChatTemplate | null, spec: DialogSaveSpec<ChatTemplateRequest>): void {
        this.dialogSave.open<ChatTemplateDialogComponent, ChatTemplate | null, ChatTemplateRequest>(
            ChatTemplateDialogComponent,
            {width: "720px", maxWidth: "95vw", data},
            spec,
        );
    }

    /** Must be called from the constructor (injection context) - takeUntilDestroyed() relies on it. */
    private notifyOn<AC extends ActionCreator<string, Creator>>(
        action: AC,
        message: (payload: ReturnType<AC>) => string,
        isError = false,
    ): void {
        this.actions$.pipe(ofType(action), takeUntilDestroyed()).subscribe(payload =>
            this.snackBar.open(message(payload), this.transloco.translate("common.ok"), {
                duration: isError ? 10000 : 3000,
                panelClass: isError ? "error-snackbar" : undefined,
            }),
        );
    }
}
