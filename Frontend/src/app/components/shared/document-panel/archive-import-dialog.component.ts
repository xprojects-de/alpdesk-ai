import {Component, inject} from "@angular/core";
import {MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {TranslocoPipe} from "@jsverse/transloco";
import {IMPORT_OUTCOME_LABELS, ImportedFile, ImportOutcome} from "../../../models/document.model";
import {countOutcomes} from "../../../utils/document.util";

export interface ArchiveImportDialogData {
    fileName: string;
    files: ImportedFile[];
}

/** What a ZIP import did: the counts per outcome, then every file of the archive with its outcome. */
@Component({
    selector: "app-archive-import-dialog",
    imports: [MatDialogModule, MatButtonModule, TranslocoPipe],
    template: `
        <h2 mat-dialog-title>{{ "documents.importDialog.title" | transloco: {name: data.fileName} }}</h2>
        <mat-dialog-content>
            <p class="summary">
                @for (outcome of outcomes; track outcome) {
                    @if (counts[outcome]; as count) {
                        <span [class]="'app-chip ' + tones[outcome]">
                            {{ count }} {{ labels[outcome] | transloco }}
                        </span>
                    }
                }
            </p>
            <p class="hint">{{ "documents.importDialog.hint" | transloco }}</p>
            <table class="files">
                @for (file of data.files; track file.path) {
                    <tr>
                        <td class="path">{{ file.path }}</td>
                        <td>
                            <span [class]="'app-chip ' + tones[file.outcome]">
                                {{ labels[file.outcome] | transloco }}
                            </span>
                        </td>
                        <td class="detail">
                            @if (file.outcome === "DUPLICATE") {
                                {{ "documents.importDialog.sameContentAs" | transloco: {path: file.detail} }}
                            } @else {
                                {{ file.detail }}
                            }
                        </td>
                    </tr>
                }
            </table>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-flat-button mat-dialog-close>{{ "common.ok" | transloco }}</button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .summary {
                display: flex;
                flex-wrap: wrap;
                gap: 8px;
                margin-top: 8px;
            }

            .hint {
                color: var(--app-text-2);
                font-size: 13px;
            }

            .files {
                width: 100%;
                border-collapse: collapse;
                font-size: 13px;
            }

            .files td {
                padding: 6px 8px 6px 0;
                border-bottom: 1px solid var(--app-border);
                vertical-align: top;
            }

            .path {
                word-break: break-all;
            }

            .detail {
                color: var(--app-text-3);
            }
        `,
    ],
})
export class ArchiveImportDialogComponent {
    readonly data = inject<ArchiveImportDialogData>(MAT_DIALOG_DATA);
    readonly labels = IMPORT_OUTCOME_LABELS;
    /** The chip's tone per outcome (app-chip classes in styles.scss): taken in green, a hint in amber, refused in red. */
    readonly tones: Record<ImportOutcome, string> = {
        CREATED: "ok",
        REPLACED: "ok",
        UNCHANGED: "",
        DUPLICATE: "warn",
        SKIPPED: "err",
    };
    readonly outcomes: ImportOutcome[] = ["CREATED", "REPLACED", "UNCHANGED", "DUPLICATE", "SKIPPED"];
    readonly counts = countOutcomes(this.data.files);
}
