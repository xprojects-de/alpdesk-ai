import {Component, inject} from "@angular/core";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MatButtonModule} from "@angular/material/button";
import {MatCheckboxModule} from "@angular/material/checkbox";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatRadioModule} from "@angular/material/radio";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {Clipboard} from "@angular/cdk/clipboard";
import {MatIconModule} from "@angular/material/icon";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTooltipModule} from "@angular/material/tooltip";
import {McpConnection, McpConnectionRequest} from "../../../models/mcp.model";
import {buildMcpConnectionRequest} from "../../../utils/mcp-connection.util";
import {notBlank} from "../../../utils/validators.util";
import {dialogSave} from "../dialog-save";

/** Create/edit dialog of a connection to the tenant's software: opened with the connection to edit, or null. */
@Component({
    selector: "app-mcp-connection-dialog",
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatButtonModule,
        MatCheckboxModule,
        MatRadioModule,
        MatIconModule,
        MatTooltipModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>
            {{ (connection ? "mcpConnections.dialog.titleEdit" : "mcpConnections.panel.connect") | transloco }}
        </h2>
        <mat-dialog-content>
            <form id="mcp-connection-form" [formGroup]="form" class="connection-form" (ngSubmit)="onSave()">
                <div class="protocol">
                    <span>{{ "software.protocol.label" | transloco }}</span>
                    <mat-radio-group
                        formControlName="protocol"
                        [attr.aria-label]="'software.protocol.label' | transloco"
                    >
                        <mat-radio-button value="MCP">{{ "software.protocol.mcp" | transloco }}</mat-radio-button>
                        <mat-radio-button value="A2A">{{ "software.protocol.a2a" | transloco }}</mat-radio-button>
                    </mat-radio-group>
                </div>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "common.name" | transloco }}</mat-label>
                    <input matInput formControlName="name" required maxlength="80" cdkFocusInitial />
                    <mat-hint>{{ "mcpConnections.dialog.nameHint" | transloco }}</mat-hint>
                    @if (form.controls.name.hasError("required") && form.controls.name.touched) {
                        <mat-error>{{ "mcpConnections.dialog.nameRequired" | transloco }}</mat-error>
                    }
                    @if (form.controls.name.hasError("blank")) {
                        <mat-error>{{ "mcpConnections.dialog.nameBlank" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{
                        (isA2a ? "software.a2aBaseUrl" : "mcpConnections.dialog.baseUrl") | transloco
                    }}</mat-label>
                    <input
                        matInput
                        formControlName="baseUrl"
                        required
                        [placeholder]="
                            (isA2a ? 'software.a2aBaseUrlPlaceholder' : 'mcpConnections.dialog.baseUrlPlaceholder')
                                | transloco
                        "
                    />
                    <mat-hint>{{
                        (isA2a ? "software.a2aBaseUrlHint" : "mcpConnections.dialog.baseUrlHint") | transloco
                    }}</mat-hint>
                    @if (form.controls.baseUrl.hasError("required") && form.controls.baseUrl.touched) {
                        <mat-error>{{ "mcpConnections.dialog.baseUrlRequired" | transloco }}</mat-error>
                    }
                </mat-form-field>
                @if (connection?.signIn; as signIn) {
                    <p class="app-note">
                        <mat-icon>login</mat-icon>
                        <span>{{ "mcpConnections.signIn.keyReplaces" | transloco: {issuer: signIn.issuer} }}</span>
                    </p>
                    <mat-checkbox formControlName="useKey">{{
                        "mcpConnections.signIn.useKey" | transloco
                    }}</mat-checkbox>
                    @if (signIn.clientIdNeeded) {
                        <p class="hint">{{ "mcpConnections.signIn.byHand" | transloco }}</p>
                        <div class="redirect">
                            <span class="mono">{{ signIn.redirectUri }}</span>
                            <button
                                mat-icon-button
                                type="button"
                                (click)="copy(signIn.redirectUri ?? '')"
                                [matTooltip]="'mcpConnections.signIn.copyRedirect' | transloco"
                                [attr.aria-label]="'mcpConnections.signIn.copyRedirect' | transloco"
                            >
                                <mat-icon>content_copy</mat-icon>
                            </button>
                        </div>
                        <mat-form-field appearance="outline">
                            <mat-label>{{ "mcpConnections.signIn.clientId" | transloco }}</mat-label>
                            <input matInput formControlName="clientId" autocomplete="off" />
                        </mat-form-field>
                        <mat-form-field appearance="outline">
                            <mat-label>{{ "mcpConnections.signIn.clientSecret" | transloco }}</mat-label>
                            <input matInput type="password" formControlName="clientSecret" autocomplete="off" />
                            <mat-hint>{{ "mcpConnections.signIn.clientSecretHint" | transloco }}</mat-hint>
                        </mat-form-field>
                    }
                }
                @if (keyShown) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{
                            (isA2a ? "software.a2aApiKey" : "mcpConnections.dialog.apiKey") | transloco
                        }}</mat-label>
                        <input matInput type="password" formControlName="apiKey" autocomplete="off" />
                        <mat-hint>
                            @if (connection?.keyHint) {
                                {{ "mcpConnections.dialog.apiKeyStored" | transloco: {hint: connection?.keyHint} }}
                            } @else {
                                {{
                                    (isA2a ? "software.a2aApiKeyOptional" : "mcpConnections.dialog.apiKeyOptional")
                                        | transloco
                                }}
                            }
                        </mat-hint>
                    </mat-form-field>
                }
                @if (connection?.keyHint) {
                    <mat-checkbox formControlName="removeApiKey">{{
                        "mcpConnections.dialog.removeApiKey" | transloco
                    }}</mat-checkbox>
                }
                @if (isA2a) {
                    <div>
                        <mat-checkbox formControlName="answersChat">{{
                            "software.answersChat" | transloco
                        }}</mat-checkbox>
                        <p class="hint">{{ "software.answersChatHint" | transloco }}</p>
                    </div>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <button
                mat-flat-button
                type="submit"
                form="mcp-connection-form"
                [disabled]="form.invalid || saver.saving()"
            >
                {{ (connection ? "common.save" : "mcpConnections.dialog.connect") | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .connection-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(480px, 70vw);
                margin-top: 16px;
            }

            mat-form-field {
                width: 100%;
            }

            .hint {
                margin: 0;
                color: var(--mat-sys-on-surface-variant);
                font-size: 12px;
            }

            .redirect {
                display: flex;
                align-items: center;
                gap: 8px;
                word-break: break-all;
            }

            .protocol {
                display: flex;
                align-items: center;
                gap: 16px;
            }
        `,
    ],
})
export class McpConnectionDialogComponent {
    private fb = inject(FormBuilder);
    private clipboard = inject(Clipboard);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);
    private dialogRef = inject<MatDialogRef<McpConnectionDialogComponent, McpConnectionRequest>>(MatDialogRef);
    /** Saves and closes only when saved - an address refused by the backend keeps everything entered. */
    readonly saver = dialogSave<McpConnectionRequest>();
    readonly connection = inject<McpConnection | null>(MAT_DIALOG_DATA);

    form = this.fb.nonNullable.group({
        name: [this.connection?.name ?? "", [Validators.required, notBlank(), Validators.maxLength(80)]],
        baseUrl: [this.connection?.baseUrl ?? "", [Validators.required, notBlank()]],
        apiKey: [""],
        removeApiKey: [false],
        protocol: [{value: this.connection?.protocol ?? "MCP", disabled: this.connection !== null}],
        answersChat: [this.connection?.answersChat ?? false],
        clientId: [""],
        clientSecret: [""],
        /** Signed in with OAuth: a key replaces the sign-in - its field shows only when asked for. */
        useKey: [false],
    });

    /** A signed-in connection shows the key's field only when asked for. */
    get keyShown(): boolean {
        return !this.connection?.signIn || this.form.controls.useKey.value;
    }

    get isA2a(): boolean {
        return this.form.controls.protocol.value === "A2A";
    }

    copy(text: string): void {
        this.clipboard.copy(text);
        this.snackBar.open(this.transloco.translate("mcpConnections.signIn.redirectCopied"), undefined, {
            duration: 2000,
        });
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        this.saver.submit(buildMcpConnectionRequest({...this.form.getRawValue(), keyShown: this.keyShown}));
    }
}
