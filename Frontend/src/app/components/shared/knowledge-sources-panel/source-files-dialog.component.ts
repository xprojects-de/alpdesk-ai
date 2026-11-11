import {Component, inject} from "@angular/core";
import {Store} from "@ngrx/store";
import {MatButtonModule} from "@angular/material/button";
import {MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {TranslocoPipe} from "@jsverse/transloco";
import {KnowledgeSource} from "../../../models/knowledge-source.model";
import {TenantScope} from "../../../models/tenant.model";
import * as SourceActions from "../../../store/knowledge-source/knowledge-source.actions";
import * as SourceSelectors from "../../../store/knowledge-source/knowledge-source.selectors";
import {sourceErrorParams, sourceFileReason} from "../../../utils/knowledge-source.util";

export interface SourceFilesDialogData {
    scope: TenantScope;
    source: KnowledgeSource;
}

/** The files of a source without a document - refused with the reason, or deleted by hand - else nobody learns why. */
@Component({
    selector: "app-source-files-dialog",
    imports: [MatDialogModule, MatButtonModule, MatProgressBarModule, TranslocoPipe],
    template: `
        <h2 mat-dialog-title>{{ "knowledgeSources.files.title" | transloco: {name: data.source.name} }}</h2>
        <mat-dialog-content>
            @if (files(); as list) {
                @if (list.sourceId === data.source.id) {
                    @if (list.files.length === 0) {
                        <p class="muted">{{ "knowledgeSources.files.none" | transloco }}</p>
                    } @else {
                        <p class="muted">{{ "knowledgeSources.files.intro" | transloco }}</p>
                        <ul>
                            @for (file of list.files; track file.path) {
                                <li>
                                    <span class="path">{{ file.path }}</span>
                                    <span class="reason">{{ reasonOf(file.reason) | transloco: params }}</span>
                                </li>
                            }
                        </ul>
                    }
                }
            } @else if (filesFailed()) {
                <p class="muted">{{ "knowledgeSources.files.failed" | transloco }}</p>
            } @else {
                <mat-progress-bar mode="indeterminate" />
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-flat-button mat-dialog-close>{{ "common.close" | transloco }}</button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            ul {
                list-style: none;
                margin: 0;
                padding: 0;
                min-width: min(560px, 70vw);
            }

            li {
                display: flex;
                flex-direction: column;
                gap: 2px;
                padding: 8px 0;
                border-top: 1px solid var(--app-border);
            }

            .path {
                font-family: var(--app-mono);
                overflow-wrap: anywhere;
            }

            .reason {
                color: var(--app-text-2);
                font-size: 13px;
            }
        `,
    ],
})
export class SourceFilesDialogComponent {
    private store = inject(Store);
    readonly data = inject<SourceFilesDialogData>(MAT_DIALOG_DATA);
    readonly files = this.store.selectSignal(SourceSelectors.selectFiles);
    readonly filesFailed = this.store.selectSignal(SourceSelectors.selectFilesFailed);
    readonly params = sourceErrorParams(this.data.source);
    readonly reasonOf = sourceFileReason;

    constructor() {
        this.store.dispatch(SourceActions.loadFiles({scope: this.data.scope, sourceId: this.data.source.id}));
    }
}
