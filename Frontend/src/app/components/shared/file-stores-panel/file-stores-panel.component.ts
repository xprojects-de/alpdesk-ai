import {Component, DestroyRef, inject, input, OnInit} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {ActionCreator, Creator, Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDatePipe} from "@jsverse/transloco-locale";
import {filter} from "rxjs/operators";
import {MatButtonModule} from "@angular/material/button";
import {MatDialog} from "@angular/material/dialog";
import {MatExpansionModule} from "@angular/material/expansion";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSnackBar} from "@angular/material/snack-bar";
import {FileStore, FileStoreCheck, FileStoreRequest} from "../../../models/file-store.model";
import {TenantScope} from "../../../models/tenant.model";
import * as FileStoreActions from "../../../store/file-store/file-store.actions";
import * as FileStoreSelectors from "../../../store/file-store/file-store.selectors";
import {storeAddress, storeStatus} from "../../../utils/file-store.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {DialogSave} from "../dialog-save";
import {FileStoreDialogComponent} from "./file-store-dialog.component";

/**
 * The file stores of one tenant (TenantScope): SFTP and FTPS servers, S3 buckets and SharePoint libraries the app reads
 * files from and writes files to. A server's check shows its fingerprint first - the tenant admin confirms it, then the password goes out and
 * the folder is read; another fingerprint later is shown as a warning, never taken silently. An S3 or SharePoint store
 * has none: its check reads the folder at once.
 */
@Component({
    selector: "app-file-stores-panel",
    imports: [
        MatButtonModule,
        MatExpansionModule,
        MatIconModule,
        MatProgressBarModule,
        EmptyStateComponent,
        TranslocoPipe,
        TranslocoDatePipe,
    ],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <span class="app-panel-title">{{ "fileStores.panel.title" | transloco }}</span>
                <span class="muted">{{ "fileStores.panel.intro" | transloco }}</span>
                <span class="spacer"></span>
                <button mat-flat-button (click)="openCreate()">
                    <mat-icon>create_new_folder</mat-icon>
                    {{ "fileStores.panel.add" | transloco }}
                </button>
            </div>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (stores().length === 0) {
                @if (!loading()) {
                    <app-empty-state
                        icon="folder_shared"
                        [title]="'fileStores.panel.emptyTitle' | transloco"
                        [text]="'fileStores.panel.emptyText' | transloco"
                    >
                        <button mat-flat-button (click)="openCreate()">
                            <mat-icon>create_new_folder</mat-icon>
                            {{ "fileStores.panel.add" | transloco }}
                        </button>
                    </app-empty-state>
                }
            } @else {
                <p class="app-note">
                    <mat-icon>info</mat-icon>
                    <span>{{ "fileStores.panel.note" | transloco }}</span>
                </p>
                <mat-accordion multi>
                    @for (store of stores(); track store.id) {
                        @let status = statusOf(store);
                        <mat-expansion-panel>
                            <mat-expansion-panel-header>
                                <mat-panel-title>{{ store.name }}</mat-panel-title>
                                <mat-panel-description>
                                    <span class="app-tag">{{ "fileStores.type." + store.type | transloco }}</span>
                                    @if (store.taskWrite) {
                                        <span class="app-tag">{{ "fileStores.panel.taskWrite" | transloco }}</span>
                                    }
                                    <span class="app-chip" [class]="status.chip">{{ status.label | transloco }}</span>
                                </mat-panel-description>
                            </mat-expansion-panel-header>

                            <div class="meta">
                                @if (store.type === "S3") {
                                    <span>{{ "fileStores.panel.bucket" | transloco }}</span>
                                    <span class="mono">{{ address(store) }}</span>
                                    <span>{{ "fileStores.panel.accessKey" | transloco }}</span>
                                    <span class="mono">{{ store.accessKeyId }}</span>
                                    <span>{{ "fileStores.panel.folder" | transloco }}</span>
                                    <span class="mono">{{ store.basePath }}</span>
                                } @else if (store.type === "SHAREPOINT") {
                                    <span>{{ "fileStores.panel.site" | transloco }}</span>
                                    <span class="mono">{{ address(store) }}</span>
                                    <span>{{ "fileStores.panel.app" | transloco }}</span>
                                    <span class="mono">{{ store.clientId }}</span>
                                    <span>{{ "fileStores.panel.folder" | transloco }}</span>
                                    <span class="mono">{{ store.basePath }}</span>
                                } @else {
                                    <span>{{ "fileStores.panel.server" | transloco }}</span>
                                    <span class="mono">{{ address(store) }}</span>
                                    <span>{{ "fileStores.panel.user" | transloco }}</span>
                                    <span class="mono">{{ store.username }}</span>
                                    <span>{{ "fileStores.panel.folder" | transloco }}</span>
                                    <span class="mono">{{ store.basePath }}</span>
                                    <span>{{ "fileStores.panel.fingerprint" | transloco }}</span>
                                    <span class="mono">{{
                                        store.fingerprint ?? ("fileStores.panel.fingerprintNone" | transloco)
                                    }}</span>
                                }
                                <span>{{ "fileStores.panel.checked" | transloco }}</span>
                                <span>
                                    @if (store.checkedAt) {
                                        {{ store.checkedAt | translocoDate: {dateStyle: "medium", timeStyle: "short"} }}
                                    } @else {
                                        {{ "fileStores.panel.checkedNever" | transloco }}
                                    }
                                </span>
                            </div>

                            @if (entries()[store.id]; as names) {
                                <div class="entries">
                                    <h3>{{ "fileStores.panel.entries" | transloco }}</h3>
                                    @if (names.length === 0) {
                                        <span class="muted">{{ "fileStores.panel.entriesNone" | transloco }}</span>
                                    }
                                    @for (name of names; track name) {
                                        <span class="mono">{{ name }}</span>
                                    }
                                </div>
                            }

                            <div class="actions">
                                <button
                                    mat-stroked-button
                                    (click)="check(store)"
                                    [disabled]="checkingId() === store.id"
                                >
                                    <mat-icon>network_check</mat-icon>
                                    {{ "fileStores.panel.check" | transloco }}
                                </button>
                                <button mat-button (click)="openEdit(store)">
                                    <mat-icon>edit</mat-icon>
                                    {{ "common.edit" | transloco }}
                                </button>
                                <button mat-button class="danger" (click)="confirmDelete(store)">
                                    <mat-icon>delete</mat-icon>
                                    {{ "common.remove" | transloco }}
                                </button>
                            </div>
                            @if (checkingId() === store.id) {
                                <mat-progress-bar mode="indeterminate" />
                            }
                        </mat-expansion-panel>
                    }
                </mat-accordion>
            }
        </div>
    `,
    styles: [
        `
            .app-note {
                margin: 12px 14px;
            }

            mat-accordion {
                --mat-expansion-container-elevation-shadow: none;
                --mat-expansion-container-shape: 0;
                --mat-expansion-container-background-color: transparent;
            }

            mat-expansion-panel {
                border-top: 1px solid var(--app-border);
            }

            mat-panel-description {
                display: flex;
                gap: 8px;
                align-items: center;
            }

            .meta {
                display: grid;
                grid-template-columns: max-content 1fr;
                gap: 4px 16px;
                color: var(--app-text-2);
                font-size: 13px;
            }

            .mono {
                font-family: var(--app-mono);
                color: var(--app-text);
                overflow-wrap: anywhere;
            }

            .entries {
                display: flex;
                flex-direction: column;
                gap: 2px;
                margin-top: 16px;
            }

            h3 {
                margin: 0 0 4px;
                font-size: 13px;
                font-weight: 600;
                color: var(--app-text-2);
            }

            .actions {
                display: flex;
                gap: 8px;
                flex-wrap: wrap;
                margin: 16px 0 8px;
            }

            .danger,
            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class FileStoresPanelComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);

    readonly scope = input.required<TenantScope>();

    readonly stores = this.store.selectSignal(FileStoreSelectors.selectStores);
    readonly loading = this.store.selectSignal(FileStoreSelectors.selectStoresLoading);
    readonly checkingId = this.store.selectSignal(FileStoreSelectors.selectCheckingId);
    readonly entries = this.store.selectSignal(FileStoreSelectors.selectEntries);
    readonly statusOf = storeStatus;
    readonly address = storeAddress;

    constructor() {
        this.notifyOn(FileStoreActions.createStoreSuccess, () => this.transloco.translate("fileStores.snack.created"));
        this.notifyOn(FileStoreActions.deleteStoreSuccess, () => this.transloco.translate("fileStores.snack.deleted"));
        for (const failure of [
            FileStoreActions.createStoreFailure,
            FileStoreActions.updateStoreFailure,
            FileStoreActions.deleteStoreFailure,
            FileStoreActions.checkStoreFailure,
            FileStoreActions.confirmFingerprintFailure,
        ]) {
            this.notifyOn(failure, ({error}) => this.transloco.translate("fileStores.snack.error", {error}), true);
        }
        // Only this tenant's checks: the admin may have left another tenant's page while its check still ran.
        this.actions$
            .pipe(
                ofType(FileStoreActions.checkStoreSuccess),
                filter(({scope}) => scope === this.scope()),
                takeUntilDestroyed(),
            )
            .subscribe(({check, error}) => this.onChecked(check, error));
        // A confirmed fingerprint is used at once: the check logs in and reads the folder.
        this.actions$
            .pipe(
                ofType(FileStoreActions.confirmFingerprintSuccess),
                filter(({scope}) => scope === this.scope()),
                takeUntilDestroyed(),
            )
            .subscribe(({store}) => this.check(store));
    }

    ngOnInit(): void {
        this.store.dispatch(FileStoreActions.loadStores({scope: this.scope()}));
    }

    openCreate(): void {
        this.dialogSave.open<FileStoreDialogComponent, FileStore | null, FileStoreRequest>(
            FileStoreDialogComponent,
            {data: null},
            {
                action: request => FileStoreActions.createStore({scope: this.scope(), request}),
                success: [FileStoreActions.createStoreSuccess],
                failure: FileStoreActions.createStoreFailure,
            },
        );
    }

    openEdit(store: FileStore): void {
        this.dialogSave.open<FileStoreDialogComponent, FileStore | null, FileStoreRequest>(
            FileStoreDialogComponent,
            {data: store},
            {
                action: request => FileStoreActions.updateStore({scope: this.scope(), storeId: store.id, request}),
                success: [FileStoreActions.updateStoreSuccess],
                failure: FileStoreActions.updateStoreFailure,
            },
        );
    }

    check(store: FileStore): void {
        this.store.dispatch(FileStoreActions.checkStore({scope: this.scope(), storeId: store.id}));
    }

    confirmDelete(store: FileStore): void {
        this.confirm({
            message: this.transloco.translate("fileStores.confirmDelete", {name: store.name}),
            confirmLabel: this.transloco.translate("common.remove"),
            confirmColor: "warn",
        }).subscribe(() => this.store.dispatch(FileStoreActions.deleteStore({scope: this.scope(), storeId: store.id})));
    }

    private onChecked(check: FileStoreCheck, error: string | null): void {
        switch (check.status) {
            case "OK":
                this.snackBar.open(
                    this.transloco.translate("fileStores.snack.ok", {count: check.entries.length}),
                    this.transloco.translate("common.ok"),
                    {duration: 4000},
                );
                return;
            case "UNCONFIRMED":
                this.askToConfirm(check, "fileStores.confirmFingerprint", "primary");
                return;
            case "CHANGED":
                this.askToConfirm(check, "fileStores.changedFingerprint", "warn");
                return;
            case "FAILED":
                this.snackBar.open(
                    this.transloco.translate("fileStores.snack.failed", {error}),
                    this.transloco.translate("common.ok"),
                    {duration: 10000, panelClass: "error-snackbar"},
                );
        }
    }

    /** Shows the fingerprint the server presented; confirming it is the only way it is taken. */
    private askToConfirm(check: FileStoreCheck, texts: string, color: "primary" | "warn"): void {
        const fingerprint = check.fingerprint;
        if (fingerprint === null) {
            return;
        }
        this.confirm({
            title: this.transloco.translate(texts + ".title"),
            message: this.transloco.translate(texts + ".message", {
                host: check.store.host,
                fingerprint,
                confirmed: check.store.fingerprint,
            }),
            confirmLabel: this.transloco.translate(texts + ".accept"),
            confirmColor: color,
        }).subscribe(() =>
            this.store.dispatch(
                FileStoreActions.confirmFingerprint({scope: this.scope(), storeId: check.store.id, fingerprint}),
            ),
        );
    }

    private confirm(data: ConfirmDialogData) {
        return this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {width: "520px", data})
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef));
    }

    /** Must be called from the constructor (injection context) - takeUntilDestroyed() relies on it. */
    private notifyOn<AC extends ActionCreator<string, Creator>>(
        action: AC,
        message: (payload: ReturnType<AC>) => string,
        isError = false,
    ): void {
        this.actions$.pipe(ofType(action), takeUntilDestroyed()).subscribe(payload =>
            this.snackBar.open(message(payload), this.transloco.translate("common.ok"), {
                duration: isError ? 10000 : 4000,
                panelClass: isError ? "error-snackbar" : undefined,
            }),
        );
    }
}
