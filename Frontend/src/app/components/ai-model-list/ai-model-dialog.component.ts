import {Component, inject} from "@angular/core";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MatDialogRef, MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatSelectModule} from "@angular/material/select";
import {MatButtonModule} from "@angular/material/button";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {MatCheckboxModule} from "@angular/material/checkbox";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {
    AiModel,
    AiModelRequest,
    AiProvider,
    hasOwnServer,
    KIND_LABELS,
    ModelKind,
    PROVIDER_LABELS,
    PROVIDERS,
} from "../../models/ai.model";
import {
    aiModelRules,
    buildAiModelRequest,
    kindForProvider,
    MAX_CONTEXT_LENGTH,
    MAX_DIMENSION,
    MIN_CONTEXT_LENGTH,
    MIN_OUTPUT_TOKENS,
    MAX_OUTPUT_TOKENS,
    providerForKind,
    takesContextLength,
} from "../../utils/ai-model.util";
import {notBlank} from "../../utils/validators.util";
import {dialogSave} from "../shared/dialog-save";
import {TranslocoPipe} from "@jsverse/transloco";

/**
 * Create/edit dialog of a catalog model: opened with the model to edit, or null to create. A model on an own server
 * (Ollama, OpenAI-compatible) gets the server's address and key here - the key is never shown again, only its hint.
 */
@Component({
    selector: "app-ai-model-dialog",
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        MatButtonModule,
        MatSlideToggleModule,
        MatCheckboxModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>{{ (model ? "models.dialog.editTitle" : "models.dialog.createTitle") | transloco }}</h2>
        <mat-dialog-content>
            <form id="ai-model-form" [formGroup]="form" class="model-form" (ngSubmit)="onSave()">
                <div class="row">
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "models.dialog.provider" | transloco }}</mat-label>
                        <mat-select formControlName="provider">
                            @for (provider of providers; track provider) {
                                <mat-option [value]="provider">{{ providerLabels[provider] | transloco }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "models.dialog.kind" | transloco }}</mat-label>
                        <mat-select formControlName="kind">
                            @for (kind of kinds; track kind) {
                                <mat-option [value]="kind">{{ kindLabels[kind] | transloco }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                </div>
                @if (form.hasError("anthropicEmbedding")) {
                    <div class="form-error">{{ "models.dialog.anthropicEmbedding" | transloco }}</div>
                }
                @if (form.hasError("bedrockOnlyChat")) {
                    <div class="form-error">{{ "models.dialog.bedrockOnlyChat" | transloco }}</div>
                }
                @if (form.hasError("imageNotGemini")) {
                    <div class="form-error">{{ "models.dialog.imageNotGemini" | transloco }}</div>
                }
                @if (form.hasError("webSearchTavily")) {
                    <div class="form-error">{{ "models.dialog.webSearchTavily" | transloco }}</div>
                }
                @if (form.hasError("rerankTei")) {
                    <div class="form-error">{{ "models.dialog.rerankTei" | transloco }}</div>
                }
                @if (form.controls.kind.value === "RERANK") {
                    <div class="capability-hint">
                        {{ "models.dialog.rerankHint" | transloco }}
                    </div>
                }
                @if (form.controls.kind.value === "WEB_SEARCH") {
                    <div class="capability-hint">
                        {{ "models.dialog.webSearchHint" | transloco }}
                    </div>
                }
                @if (form.controls.kind.value === "IMAGE") {
                    <div class="capability-hint">
                        {{ "models.dialog.imageHint" | transloco }}
                    </div>
                }
                <mat-form-field appearance="outline">
                    <mat-label>{{ "models.dialog.displayName" | transloco }}</mat-label>
                    <input matInput formControlName="displayName" required cdkFocusInitial />
                    @if (form.controls.displayName.hasError("required") && form.controls.displayName.touched) {
                        <mat-error>{{ "models.dialog.displayNameRequired" | transloco }}</mat-error>
                    }
                    @if (form.controls.displayName.hasError("blank")) {
                        <mat-error>{{ "models.dialog.displayNameBlank" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "models.dialog.modelName" | transloco }}</mat-label>
                    <input
                        matInput
                        formControlName="modelName"
                        required
                        [placeholder]="'models.dialog.modelNamePlaceholder' | transloco"
                    />
                    <mat-hint>{{ "models.dialog.modelNameHint" | transloco }}</mat-hint>
                    @if (form.controls.modelName.hasError("required") && form.controls.modelName.touched) {
                        <mat-error>{{ "models.dialog.modelNameRequired" | transloco }}</mat-error>
                    }
                    @if (form.controls.modelName.hasError("blank")) {
                        <mat-error>{{ "models.dialog.modelNameBlank" | transloco }}</mat-error>
                    }
                </mat-form-field>
                @if (ownServer()) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "models.dialog.baseUrl" | transloco }}</mat-label>
                        <input
                            matInput
                            formControlName="baseUrl"
                            required
                            [placeholder]="baseUrlPlaceholder() | transloco"
                        />
                        <mat-hint>{{ "models.dialog.baseUrlHint" | transloco }}</mat-hint>
                    </mat-form-field>
                    @if (form.hasError("baseUrlRequired") && form.controls.baseUrl.touched) {
                        <div class="form-error">{{ "models.dialog.baseUrlRequired" | transloco }}</div>
                    }
                    @if (form.hasError("baseUrlInvalid")) {
                        <div class="form-error">
                            {{ "models.dialog.baseUrlInvalid" | transloco }}
                        </div>
                    }
                    @if (form.hasError("bedrockAddress")) {
                        <div class="form-error">{{ "models.dialog.bedrockAddress" | transloco }}</div>
                    }
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "models.dialog.apiKey" | transloco }}</mat-label>
                        <input matInput type="password" formControlName="apiKey" autocomplete="off" />
                        <mat-hint>
                            @if (model?.keyHint) {
                                {{ "models.dialog.apiKeyStored" | transloco: {hint: model?.keyHint} }}
                            } @else if (bedrock()) {
                                {{ "models.dialog.bedrockKeyHint" | transloco }}
                            } @else {
                                {{ "models.dialog.apiKeyOptional" | transloco }}
                            }
                        </mat-hint>
                    </mat-form-field>
                    @if (form.hasError("bedrockKey")) {
                        <div class="form-error">{{ "models.dialog.bedrockKey" | transloco }}</div>
                    }
                    @if (model?.keyHint) {
                        <mat-checkbox formControlName="removeApiKey">{{
                            "models.dialog.removeApiKey" | transloco
                        }}</mat-checkbox>
                    }
                    @if (takesContextLength()) {
                        <mat-form-field appearance="outline">
                            <mat-label>{{ "models.dialog.contextLength" | transloco }}</mat-label>
                            <input
                                matInput
                                type="number"
                                formControlName="contextLength"
                                [min]="minContextLength"
                                [max]="maxContextLength"
                                placeholder="32768"
                            />
                            <span matTextSuffix>{{ "models.dialog.tokensSuffix" | transloco }}</span>
                            <mat-hint>{{ "models.dialog.contextLengthHint" | transloco }}</mat-hint>
                        </mat-form-field>
                        @if (form.hasError("contextLengthRange")) {
                            <div class="form-error">
                                {{
                                    "models.dialog.contextLengthRange"
                                        | transloco: {min: minContextLength, max: maxContextLength}
                                }}
                            </div>
                        }
                    }
                }
                @if (form.controls.kind.value === "EMBEDDING") {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "models.dialog.dimension" | transloco }}</mat-label>
                        <input matInput type="number" formControlName="dimension" min="1" [max]="maxDimension" />
                        <mat-hint>
                            {{ "models.dialog.dimensionHint" | transloco: {max: maxDimension} }}
                        </mat-hint>
                    </mat-form-field>
                    @if (form.hasError("dimensionRequired") && form.controls.dimension.touched) {
                        <div class="form-error">{{ "models.dialog.dimensionRequired" | transloco }}</div>
                    }
                    @if (form.hasError("dimensionRange")) {
                        <div class="form-error">
                            {{ "models.dialog.dimensionRange" | transloco: {max: maxDimension} }}
                        </div>
                    }
                }
                @if (form.controls.kind.value === "CHAT") {
                    <fieldset class="capabilities">
                        <legend>{{ "models.dialog.readsDirectly" | transloco }}</legend>
                        <mat-checkbox formControlName="readsPdf">{{ "models.dialog.pdf" | transloco }}</mat-checkbox>
                        <mat-checkbox formControlName="readsAudio">{{
                            "models.dialog.audio" | transloco
                        }}</mat-checkbox>
                        <div class="capability-hint">{{ "models.dialog.imagesHint" | transloco }}</div>
                        <mat-checkbox formControlName="transcribes">
                            {{ "models.dialog.transcribes" | transloco }}
                        </mat-checkbox>
                        <mat-checkbox formControlName="readsScans">
                            {{ "models.dialog.readsScans" | transloco }}
                        </mat-checkbox>
                    </fieldset>
                    <mat-checkbox formControlName="takesTemperature">
                        {{ "models.dialog.takesTemperature" | transloco }}
                    </mat-checkbox>
                    <div class="capability-hint">
                        {{ "models.dialog.takesTemperatureHint" | transloco }}
                    </div>
                    <mat-checkbox formControlName="steersReasoning">
                        {{ "models.dialog.steersReasoning" | transloco }}
                    </mat-checkbox>
                    <div class="capability-hint">
                        {{ "models.dialog.steersReasoningHint" | transloco }}
                    </div>
                    <mat-checkbox formControlName="callsTools">{{
                        "models.dialog.callsTools" | transloco
                    }}</mat-checkbox>
                    <div class="capability-hint">
                        {{ "models.dialog.callsToolsHint" | transloco }}
                    </div>
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "models.dialog.maxOutputTokens" | transloco }}</mat-label>
                        <input
                            matInput
                            type="number"
                            formControlName="maxOutputTokens"
                            [min]="minOutputTokens"
                            [max]="maxOutputTokens"
                            placeholder="16384"
                        />
                        <span matTextSuffix>{{ "models.dialog.tokensSuffix" | transloco }}</span>
                        <mat-hint>
                            {{ "models.dialog.maxOutputTokensHint" | transloco }}
                        </mat-hint>
                    </mat-form-field>
                    @if (form.hasError("maxOutputTokensRange")) {
                        <div class="form-error">
                            {{
                                "models.dialog.maxOutputTokensRange"
                                    | transloco: {min: minOutputTokens, max: maxOutputTokens}
                            }}
                        </div>
                    }
                }
                <div class="row">
                    <mat-form-field appearance="outline">
                        <mat-label>{{
                            (webSearch() ? "models.dialog.webSearchPrice" : "models.dialog.inputPrice") | transloco
                        }}</mat-label>
                        <input matInput type="number" formControlName="inputPrice" min="0" step="0.01" required />
                        <span matTextSuffix>{{ "models.dialog.euroSuffix" | transloco }}</span>
                        <mat-hint>{{
                            (webSearch() ? "models.dialog.perThousandSearches" : "models.dialog.perMillion") | transloco
                        }}</mat-hint>
                        @if (form.controls.inputPrice.hasError("min")) {
                            <mat-error>{{ "models.dialog.notNegative" | transloco }}</mat-error>
                        }
                    </mat-form-field>
                    @if (!webSearch()) {
                        <mat-form-field appearance="outline">
                            <mat-label>{{ "models.dialog.outputPrice" | transloco }}</mat-label>
                            <input matInput type="number" formControlName="outputPrice" min="0" step="0.01" required />
                            <span matTextSuffix>{{ "models.dialog.euroSuffix" | transloco }}</span>
                            <mat-hint>{{ "models.dialog.perMillion" | transloco }}</mat-hint>
                            @if (form.controls.outputPrice.hasError("min")) {
                                <mat-error>{{ "models.dialog.notNegative" | transloco }}</mat-error>
                            }
                        </mat-form-field>
                    }
                </div>
                @if (!webSearch()) {
                    <fieldset class="capabilities">
                        <legend>{{ "models.dialog.tiersAndCache" | transloco }}</legend>
                        <div class="capability-hint">{{ "models.dialog.tiersAndCacheHint" | transloco }}</div>
                        <mat-form-field appearance="outline">
                            <mat-label>{{ "models.dialog.tierThreshold" | transloco }}</mat-label>
                            <input
                                matInput
                                type="number"
                                formControlName="tierThreshold"
                                min="1"
                                placeholder="100000"
                            />
                            <span matTextSuffix>{{ "models.dialog.tokensSuffix" | transloco }}</span>
                            <mat-hint>{{ "models.dialog.tierThresholdHint" | transloco }}</mat-hint>
                        </mat-form-field>
                        @if (form.hasError("tierThresholdRange")) {
                            <div class="form-error">{{ "models.dialog.tierThresholdRange" | transloco }}</div>
                        }
                        @if (form.hasError("tierIncomplete")) {
                            <div class="form-error">{{ "models.dialog.tierIncomplete" | transloco }}</div>
                        }
                        @for (pair of pricePairs; track pair[0]) {
                            <div class="row">
                                @for (field of pair; track field) {
                                    <mat-form-field appearance="outline">
                                        <mat-label>{{ "models.dialog." + field | transloco }}</mat-label>
                                        <input matInput type="number" [formControlName]="field" min="0" step="0.01" />
                                        <span matTextSuffix>{{ "models.dialog.euroSuffix" | transloco }}</span>
                                        <mat-hint>{{ "models.dialog.perMillion" | transloco }}</mat-hint>
                                        @if (form.controls[field].hasError("min")) {
                                            <mat-error>{{ "models.dialog.notNegative" | transloco }}</mat-error>
                                        }
                                    </mat-form-field>
                                }
                            </div>
                        }
                    </fieldset>
                }
                <mat-slide-toggle formControlName="active">{{ "models.dialog.active" | transloco }}</mat-slide-toggle>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button type="submit" form="ai-model-form" [disabled]="form.invalid || saver.saving()">
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .model-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(520px, 75vw);
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

            .form-error {
                color: var(--app-err);
                font-size: 13px;
                margin: -4px 0 8px;
            }

            mat-slide-toggle {
                margin: 4px 0 8px;
            }

            .capabilities {
                border: 1px solid var(--app-border);
                border-radius: 8px;
                padding: 4px 12px 8px;
                margin: 0 0 12px;
            }

            .capabilities legend {
                font-size: 13px;
                color: var(--app-text-2);
                padding: 0 4px;
            }

            .capability-hint {
                font-size: 12px;
                color: var(--app-text-2);
                margin: 0 0 4px 8px;
            }
        `,
    ],
})
export class AiModelDialogComponent {
    private fb = inject(FormBuilder);
    private dialogRef = inject<MatDialogRef<AiModelDialogComponent, AiModelRequest>>(MatDialogRef);
    readonly saver = dialogSave<AiModelRequest>();
    readonly model = inject<AiModel | null>(MAT_DIALOG_DATA);

    readonly providers = PROVIDERS;
    readonly providerLabels = PROVIDER_LABELS;
    readonly kinds: ModelKind[] = ["CHAT", "EMBEDDING", "IMAGE", "WEB_SEARCH", "RERANK"];
    readonly kindLabels = KIND_LABELS;
    readonly maxDimension = MAX_DIMENSION;
    readonly minContextLength = MIN_CONTEXT_LENGTH;
    readonly minOutputTokens = MIN_OUTPUT_TOKENS;
    readonly maxOutputTokens = MAX_OUTPUT_TOKENS;
    readonly maxContextLength = MAX_CONTEXT_LENGTH;
    /** The tier's and the cache's prices, two by two - below the threshold first, then above it. */
    readonly pricePairs = [
        ["tierInputPrice", "tierOutputPrice"],
        ["cacheReadPrice", "cacheWritePrice"],
        ["tierCacheReadPrice", "tierCacheWritePrice"],
    ] as const;

    form = this.fb.group(
        {
            provider: this.fb.nonNullable.control<AiProvider>(this.model?.provider ?? "OPENAI"),
            kind: this.fb.nonNullable.control<ModelKind>(this.model?.kind ?? "CHAT"),
            displayName: this.fb.nonNullable.control(this.model?.displayName ?? "", [Validators.required, notBlank()]),
            modelName: this.fb.nonNullable.control(this.model?.modelName ?? "", [Validators.required, notBlank()]),
            dimension: this.fb.control<number | null>(this.model?.dimension ?? null),
            inputPrice: this.fb.control<number | null>(this.model?.inputPrice ?? null, [
                Validators.required,
                Validators.min(0),
            ]),
            outputPrice: this.fb.control<number | null>(this.model?.outputPrice ?? null, [
                Validators.required,
                Validators.min(0),
            ]),
            readsPdf: this.fb.nonNullable.control(this.model?.readsPdf ?? false),
            readsAudio: this.fb.nonNullable.control(this.model?.readsAudio ?? false),
            transcribes: this.fb.nonNullable.control({
                value: this.model?.transcribes ?? false,
                disabled: !this.model?.readsAudio,
            }),
            readsScans: this.fb.nonNullable.control(this.model?.readsScans ?? false),
            takesTemperature: this.fb.nonNullable.control(this.model?.takesTemperature ?? true),
            steersReasoning: this.fb.nonNullable.control(this.model?.steersReasoning ?? false),
            callsTools: this.fb.nonNullable.control(this.model?.callsTools ?? true),
            baseUrl: this.fb.nonNullable.control(this.model?.baseUrl ?? ""),
            apiKey: this.fb.nonNullable.control(""),
            removeApiKey: this.fb.nonNullable.control(false),
            contextLength: this.fb.control<number | null>(this.model?.contextLength ?? null),
            maxOutputTokens: this.fb.control<number | null>(this.model?.maxOutputTokens ?? null),
            tierThreshold: this.fb.control<number | null>(this.model?.tierThreshold ?? null),
            tierInputPrice: this.fb.control<number | null>(this.model?.tierInputPrice ?? null, Validators.min(0)),
            tierOutputPrice: this.fb.control<number | null>(this.model?.tierOutputPrice ?? null, Validators.min(0)),
            cacheReadPrice: this.fb.control<number | null>(this.model?.cacheReadPrice ?? null, Validators.min(0)),
            cacheWritePrice: this.fb.control<number | null>(this.model?.cacheWritePrice ?? null, Validators.min(0)),
            tierCacheReadPrice: this.fb.control<number | null>(
                this.model?.tierCacheReadPrice ?? null,
                Validators.min(0),
            ),
            tierCacheWritePrice: this.fb.control<number | null>(
                this.model?.tierCacheWritePrice ?? null,
                Validators.min(0),
            ),
            active: this.fb.nonNullable.control(this.model?.active ?? true),
        },
        {validators: aiModelRules()},
    );

    constructor() {
        // Each set only when it differs - otherwise the two subscriptions would set each other forever.
        this.form.controls.kind.valueChanges.pipe(takeUntilDestroyed()).subscribe(kind => {
            const provider = providerForKind(kind);
            if (provider && provider !== this.form.controls.provider.value) {
                this.form.controls.provider.setValue(provider);
            }
        });
        this.form.controls.provider.valueChanges.pipe(takeUntilDestroyed()).subscribe(provider => {
            const kind = kindForProvider(provider);
            if (kind && kind !== this.form.controls.kind.value) {
                this.form.controls.kind.setValue(kind);
            }
        });
        // The web search has one price: the hidden output price must not keep the form invalid.
        this.form.controls.kind.valueChanges.pipe(takeUntilDestroyed()).subscribe(kind => {
            if (kind === "WEB_SEARCH" && this.form.controls.outputPrice.value === null) {
                this.form.controls.outputPrice.setValue(0);
            }
        });
        // Transcribing needs audio: without it the box is cleared and locked.
        this.form.controls.readsAudio.valueChanges.pipe(takeUntilDestroyed()).subscribe(readsAudio => {
            const transcribes = this.form.controls.transcribes;
            if (readsAudio) {
                transcribes.enable();
            } else {
                transcribes.setValue(false);
                transcribes.disable();
            }
        });
    }

    webSearch(): boolean {
        return this.form.controls.kind.value === "WEB_SEARCH";
    }

    /** A translation key: an example address of the provider's server. */
    baseUrlPlaceholder(): string {
        switch (this.form.controls.provider.value) {
            case "OLLAMA":
                return "models.dialog.baseUrlPlaceholderOllama";
            case "TEI":
                return "models.dialog.baseUrlPlaceholderTei";
            case "BEDROCK":
                return "models.dialog.baseUrlPlaceholderBedrock";
            default:
                return "models.dialog.baseUrlPlaceholderCompatible";
        }
    }

    bedrock(): boolean {
        return this.form.controls.provider.value === "BEDROCK";
    }

    ownServer(): boolean {
        return hasOwnServer(this.form.controls.provider.value);
    }

    takesContextLength(): boolean {
        return takesContextLength(this.form.getRawValue());
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        this.saver.submit(buildAiModelRequest(this.form.getRawValue()));
    }
}
