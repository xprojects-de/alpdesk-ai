import {Component, computed, inject, OnInit} from "@angular/core";
import {Store} from "@ngrx/store";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {MatButtonModule} from "@angular/material/button";
import {MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {TenantScope} from "../../models/tenant.model";
import * as LanguageSelectors from "../../store/language/language.selectors";
import * as TaskActions from "../../store/task/task.actions";
import * as TaskSelectors from "../../store/task/task.selectors";
import {renderMarkdown} from "../../utils/markdown.util";

/**
 * The task format's reference in the frontend's language - fields, triggers, steps, conditions, examples. The backend
 * keeps it (resources/tasks/reference): a draft's model is told the same text.
 */
@Component({
    selector: "app-task-reference-dialog",
    imports: [MatButtonModule, MatDialogModule, MatProgressBarModule, TranslocoPipe],
    template: `
        <h2 mat-dialog-title>{{ "tasks.reference.title" | transloco }}</h2>
        <mat-dialog-content>
            @if (html(); as html) {
                <div class="markdown" [innerHTML]="html"></div>
            } @else {
                <mat-progress-bar mode="indeterminate" />
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>{{ "common.close" | transloco }}</button>
        </mat-dialog-actions>
    `,
})
export class TaskReferenceDialogComponent implements OnInit {
    private store = inject(Store);
    private transloco = inject(TranslocoService);
    private scope = inject<TenantScope>(MAT_DIALOG_DATA);
    private readonly language = this.store.selectSignal(LanguageSelectors.selectLanguage);
    private readonly markdown = this.store.selectSignal(TaskSelectors.selectReference);

    readonly html = computed(() => {
        const markdown = this.markdown();
        return markdown === null
            ? null
            : renderMarkdown(markdown, {
                  copy: this.transloco.translate("common.copy"),
                  title: this.transloco.translate("chat.answer.copyCode"),
              });
    });

    ngOnInit(): void {
        this.store.dispatch(TaskActions.loadReference({scope: this.scope, language: this.language()}));
    }
}
