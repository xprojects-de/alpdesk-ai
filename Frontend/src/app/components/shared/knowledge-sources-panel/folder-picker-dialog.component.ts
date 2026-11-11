import {Component, computed, inject} from "@angular/core";
import {Store} from "@ngrx/store";
import {MatButtonModule} from "@angular/material/button";
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from "@angular/material/dialog";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {TranslocoPipe} from "@jsverse/transloco";
import {FileStore} from "../../../models/file-store.model";
import {TenantScope} from "../../../models/tenant.model";
import * as FileStoreActions from "../../../store/file-store/file-store.actions";
import * as FileStoreSelectors from "../../../store/file-store/file-store.selectors";
import {childFolder, folderSteps} from "../../../utils/file-store.util";

export interface FolderPickerData {
    scope: TenantScope;
    store: FileStore;
    /** The folder to start in - the one typed so far. */
    path: string;
}

/**
 * Chooses a folder of a store by clicking through its subfolders - closed with the chosen path (relative to the
 * store's folder), or nothing. Each step asks the server; the names are shown, not kept.
 */
@Component({
    selector: "app-folder-picker-dialog",
    imports: [MatDialogModule, MatButtonModule, MatIconModule, MatProgressBarModule, TranslocoPipe],
    template: `
        <h2 mat-dialog-title>{{ "knowledgeSources.folderPicker.title" | transloco }}</h2>
        <mat-dialog-content>
            <nav class="steps" [attr.aria-label]="'knowledgeSources.folderPicker.path' | transloco">
                <button mat-button type="button" (click)="open('')">
                    <mat-icon>folder_shared</mat-icon>
                    {{ data.store.name }}
                </button>
                @for (step of steps(); track step.path) {
                    <mat-icon class="separator">chevron_right</mat-icon>
                    <button mat-button type="button" (click)="open(step.path)">{{ step.name }}</button>
                }
            </nav>

            @if (browsing(); as current) {
                @if (current.error) {
                    <p class="app-note warn">
                        <mat-icon>error</mat-icon>
                        <span>{{ current.error }}</span>
                    </p>
                } @else if (current.listing; as listing) {
                    @if (listing.folders.length === 0) {
                        <p class="muted">{{ "knowledgeSources.folderPicker.empty" | transloco }}</p>
                    } @else {
                        <ul class="folders">
                            @for (name of listing.folders; track name) {
                                <li>
                                    <button type="button" class="folder" (click)="open(child(name))">
                                        <mat-icon>folder</mat-icon>
                                        <span>{{ name }}</span>
                                        <mat-icon class="enter">chevron_right</mat-icon>
                                    </button>
                                </li>
                            }
                        </ul>
                        @if (listing.truncated) {
                            <p class="muted">{{ "knowledgeSources.folderPicker.truncated" | transloco }}</p>
                        }
                    }
                } @else {
                    <mat-progress-bar mode="indeterminate" />
                }
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="close()">{{ "common.cancel" | transloco }}</button>
            <button mat-flat-button (click)="close(path())" [disabled]="!browsing()?.listing">
                {{ "knowledgeSources.folderPicker.take" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .steps {
                display: flex;
                align-items: center;
                flex-wrap: wrap;
                gap: 2px;
                margin-bottom: 8px;
                min-width: min(520px, 70vw);
            }

            .separator {
                color: var(--app-text-2);
            }

            .folders {
                list-style: none;
                margin: 0;
                padding: 0;
                max-height: 50vh;
                overflow-y: auto;
            }

            .folder {
                display: flex;
                align-items: center;
                gap: 10px;
                width: 100%;
                padding: 8px 6px;
                border: none;
                border-top: 1px solid var(--app-border);
                background: none;
                color: var(--app-text);
                font: inherit;
                text-align: left;
                cursor: pointer;
            }

            .folder:hover {
                background: var(--app-brand-softer);
            }

            .folder span {
                flex: 1;
                overflow-wrap: anywhere;
            }

            .enter {
                color: var(--app-text-2);
            }
        `,
    ],
})
export class FolderPickerDialogComponent {
    private store = inject(Store);
    private dialogRef = inject<MatDialogRef<FolderPickerDialogComponent, string>>(MatDialogRef);
    readonly data = inject<FolderPickerData>(MAT_DIALOG_DATA);
    readonly browsing = this.store.selectSignal(FileStoreSelectors.selectBrowsing);
    /** The folder looked into - as the server named it once listed. */
    readonly path = computed(() => this.browsing()?.listing?.path ?? this.browsing()?.path ?? "");
    readonly steps = computed(() => folderSteps(this.path()));

    constructor() {
        this.open(this.data.path);
    }

    open(path: string): void {
        this.store.dispatch(
            FileStoreActions.browseFolders({scope: this.data.scope, storeId: this.data.store.id, path}),
        );
    }

    child(name: string): string {
        return childFolder(this.path(), name);
    }

    close(path?: string): void {
        this.dialogRef.close(path);
    }
}
