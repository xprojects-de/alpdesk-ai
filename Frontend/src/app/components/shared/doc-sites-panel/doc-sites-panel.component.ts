import {Component, DestroyRef, inject, input, OnInit} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDatePipe} from "@jsverse/transloco-locale";
import {ActionCreator, Creator, Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {filter} from "rxjs/operators";
import {MatDialog} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatTableModule} from "@angular/material/table";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatSnackBar} from "@angular/material/snack-bar";
import {TenantScope} from "../../../models/tenant.model";
import {DocSite} from "../../../models/doc-site.model";
import * as SiteActions from "../../../store/doc-site/doc-site.actions";
import * as SiteSelectors from "../../../store/doc-site/doc-site.selectors";
import * as AreaActions from "../../../store/knowledge-area/knowledge-area.actions";
import * as AreaSelectors from "../../../store/knowledge-area/knowledge-area.selectors";
import {siteLabel, siteSize} from "../../../utils/doc-site.util";
import {AreaText} from "../../../utils/knowledge-area.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {DialogSave} from "../dialog-save";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {
    DocSiteUploadDialogComponent,
    DocSiteUploadDialogData,
    DocSiteUploadValue,
} from "./doc-site-upload-dialog.component";
import {DocSitePreviewDialogComponent, DocSitePreviewDialogData} from "./doc-site-preview-dialog.component";

/**
 * One tenant's handbook websites - one for everyone and one per knowledge area, each uploaded as a ZIP: the tenant
 * admin's handbook page (scope "self") or the admin's tab of the tenant (a tenant's id).
 */
@Component({
    selector: "app-doc-sites-panel",
    imports: [
        TranslocoPipe,
        TranslocoDatePipe,
        MatButtonModule,
        MatIconModule,
        MatTableModule,
        MatTooltipModule,
        MatProgressBarModule,
        EmptyStateComponent,
    ],
    template: `
        <div class="app-panel">
            <div class="app-panel-head">
                <div>
                    <div class="app-panel-title">{{ "handbook.panel.title" | transloco }}</div>
                    <div class="muted">{{ "handbook.panel.subtitle" | transloco }}</div>
                </div>
                <span class="spacer"></span>
                <button mat-stroked-button (click)="openUpload(null)" [disabled]="uploading()">
                    <mat-icon>upload</mat-icon>
                    {{ "handbook.panel.upload" | transloco }}
                </button>
            </div>

            @if (loading() || uploading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (sites().length === 0 && !loading()) {
                <app-empty-state
                    icon="auto_stories"
                    [title]="'handbook.panel.empty.title' | transloco"
                    [text]="'handbook.panel.empty.text' | transloco"
                >
                    <button mat-flat-button (click)="openUpload(null)" [disabled]="uploading()">
                        <mat-icon>upload</mat-icon>
                        {{ "handbook.panel.upload" | transloco }}
                    </button>
                </app-empty-state>
            } @else {
                <div class="table-container">
                    <table mat-table [dataSource]="sites()" [trackBy]="trackById" class="sites-table">
                        <ng-container matColumnDef="scope">
                            <th mat-header-cell *matHeaderCellDef>{{ "handbook.panel.column.scope" | transloco }}</th>
                            <td mat-cell *matCellDef="let site">
                                @let label = labelOf(site);
                                <div class="cell-title">{{ label.key | transloco: label.params }}</div>
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="files">
                            <th mat-header-cell *matHeaderCellDef>{{ "handbook.panel.column.files" | transloco }}</th>
                            <td mat-cell *matCellDef="let site" class="muted">
                                @let size = sizeOf(site);
                                {{ "handbook.panel.files" | transloco: {count: site.fileCount} }} ·
                                {{ size.key | transloco: size.params }}
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="uploaded">
                            <th mat-header-cell *matHeaderCellDef>
                                {{ "handbook.panel.column.uploaded" | transloco }}
                            </th>
                            <td mat-cell *matCellDef="let site" class="muted">
                                {{ site.uploadedAt | translocoDate: {dateStyle: "medium", timeStyle: "short"} }}
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="actions">
                            <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                            <td mat-cell *matCellDef="let site" class="actions-column">
                                <button
                                    mat-icon-button
                                    (click)="openPreview(site)"
                                    [matTooltip]="'handbook.panel.view' | transloco"
                                >
                                    <mat-icon>visibility</mat-icon>
                                </button>
                                <button
                                    mat-icon-button
                                    (click)="openUpload(site.areaId)"
                                    [disabled]="uploading()"
                                    [matTooltip]="'handbook.panel.replace' | transloco"
                                >
                                    <mat-icon>upload</mat-icon>
                                </button>
                                <button
                                    mat-icon-button
                                    class="danger"
                                    (click)="confirmDelete(site)"
                                    [matTooltip]="'handbook.panel.remove' | transloco"
                                >
                                    <mat-icon>delete</mat-icon>
                                </button>
                            </td>
                        </ng-container>
                        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                        <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
                    </table>
                </div>
            }
        </div>
    `,
    styles: [
        `
            .sites-table {
                width: 100%;
                min-width: min(640px, 80vw);
            }

            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class DocSitesPanelComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);
    private destroyRef = inject(DestroyRef);
    readonly scope = input.required<TenantScope>();

    readonly sites = this.store.selectSignal(SiteSelectors.selectSites);
    readonly loading = this.store.selectSignal(SiteSelectors.selectSitesLoading);
    readonly uploading = this.store.selectSignal(SiteSelectors.selectUploading);
    readonly maxZipBytes = this.store.selectSignal(SiteSelectors.selectMaxZipBytes);
    readonly areas = this.store.selectSignal(AreaSelectors.selectAreas);

    readonly displayedColumns = ["scope", "files", "uploaded", "actions"];
    readonly trackById = (_index: number, site: DocSite) => site.id;

    labelOf(site: DocSite): AreaText {
        return siteLabel(site);
    }

    sizeOf(site: DocSite): AreaText {
        return siteSize(site.sizeBytes);
    }

    constructor() {
        this.actions$.pipe(ofType(SiteActions.uploadSiteSuccess), takeUntilDestroyed()).subscribe(({upload}) =>
            this.snack(
                upload.skipped.length === 0
                    ? this.translate("handbook.snack.uploaded")
                    : this.translate("handbook.snack.uploadedSkipped", {
                          count: upload.skipped.length,
                          paths: upload.skipped
                              .slice(0, 3)
                              .map(file => file.path)
                              .join(", "),
                      }),
                upload.skipped.length > 0,
            ),
        );
        this.notifyOn(SiteActions.deleteSiteSuccess, () => this.translate("handbook.snack.removed"));
        this.notifyOn(
            SiteActions.uploadSiteFailure,
            ({error}) => this.translate("handbook.snack.uploadError", {error}),
            true,
        );
        this.notifyOn(
            SiteActions.deleteSiteFailure,
            ({error}) => this.translate("handbook.snack.removeError", {error}),
            true,
        );
    }

    ngOnInit(): void {
        this.store.dispatch(SiteActions.loadSites({scope: this.scope()}));
        this.store.dispatch(AreaActions.loadAreas({scope: this.scope()}));
    }

    openUpload(areaId: number | null): void {
        this.dialogSave.open<DocSiteUploadDialogComponent, DocSiteUploadDialogData, DocSiteUploadValue>(
            DocSiteUploadDialogComponent,
            {width: "520px", data: {areas: this.areas(), areaId, maxZipBytes: this.maxZipBytes()}},
            {
                action: ({file, areaId: chosen}) => SiteActions.uploadSite({scope: this.scope(), file, areaId: chosen}),
                success: [SiteActions.uploadSiteSuccess],
                failure: SiteActions.uploadSiteFailure,
            },
        );
    }

    openPreview(site: DocSite): void {
        const label = siteLabel(site);
        this.dialog.open<DocSitePreviewDialogComponent, DocSitePreviewDialogData>(DocSitePreviewDialogComponent, {
            width: "96vw",
            maxWidth: "96vw",
            height: "92vh",
            data: {scope: this.scope(), siteId: site.id, title: this.translate(label.key, label.params)},
        });
    }

    confirmDelete(site: DocSite): void {
        const label = siteLabel(site);
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.translate("handbook.confirmRemove", {
                        name: this.translate(label.key, label.params),
                    }),
                    confirmLabel: this.translate("handbook.panel.remove"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(SiteActions.deleteSite({scope: this.scope(), id: site.id})));
    }

    /** Must be called from the constructor (injection context) - takeUntilDestroyed() relies on it. */
    private notifyOn<AC extends ActionCreator<string, Creator>>(
        action: AC,
        message: (payload: ReturnType<AC>) => string,
        isError = false,
    ): void {
        this.actions$
            .pipe(ofType(action), takeUntilDestroyed())
            .subscribe(payload => this.snack(message(payload), isError));
    }

    private snack(message: string, isError: boolean): void {
        this.snackBar.open(message, this.translate("common.ok"), {
            duration: isError ? 10000 : 3000,
            panelClass: isError ? "error-snackbar" : undefined,
        });
    }

    private translate(key: string, params?: Record<string, unknown>): string {
        return this.transloco.translate(key, params);
    }
}
