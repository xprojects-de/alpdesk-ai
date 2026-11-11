import {Component, inject, OnDestroy} from "@angular/core";
import {MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {Store} from "@ngrx/store";
import {TranslocoPipe} from "@jsverse/transloco";
import {TenantScope} from "../../../models/tenant.model";
import * as SiteActions from "../../../store/doc-site/doc-site.actions";
import * as SiteSelectors from "../../../store/doc-site/doc-site.selectors";
import {DocSiteFrameComponent} from "./doc-site-frame.component";

export interface DocSitePreviewDialogData {
    scope: TenantScope;
    siteId: number;
    title: string;
}

/** A website as its readers see it - for the tenant's admins, who keep it. */
@Component({
    selector: "app-doc-site-preview-dialog",
    imports: [MatDialogModule, MatButtonModule, MatProgressBarModule, TranslocoPipe, DocSiteFrameComponent],
    template: `
        <h2 mat-dialog-title>{{ data.title }}</h2>
        <mat-dialog-content class="content">
            @if (opened(); as site) {
                @if (site.siteId === data.siteId) {
                    <app-doc-site-frame class="frame" [url]="site.url" [title]="data.title" />
                }
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
            /* Nearly the whole window: the dialog is opened 96vw x 92vh, minus its title and buttons. */
            .content {
                height: calc(92vh - 130px);
                max-height: none;
            }

            .frame {
                height: 100%;
                border: 1px solid var(--app-border);
                border-radius: 8px;
                overflow: hidden;
            }
        `,
    ],
})
export class DocSitePreviewDialogComponent implements OnDestroy {
    private store = inject(Store);
    readonly data = inject<DocSitePreviewDialogData>(MAT_DIALOG_DATA);
    readonly opened = this.store.selectSignal(SiteSelectors.selectPreviewSite);

    constructor() {
        this.store.dispatch(SiteActions.openSite({scope: this.data.scope, id: this.data.siteId}));
    }

    ngOnDestroy(): void {
        this.store.dispatch(SiteActions.closeSite({scope: this.data.scope}));
    }
}
