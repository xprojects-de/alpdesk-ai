import {
    afterNextRender,
    Component,
    computed,
    DestroyRef,
    ElementRef,
    inject,
    input,
    OnInit,
    signal,
    viewChild,
} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {ActionCreator, Creator, Store} from "@ngrx/store";
import {Actions, ofType} from "@ngrx/effects";
import {filter} from "rxjs/operators";
import {MatTableModule} from "@angular/material/table";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatProgressBarModule} from "@angular/material/progress-bar";
import {MatDialog} from "@angular/material/dialog";
import {MatSnackBar} from "@angular/material/snack-bar";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MatMenuModule} from "@angular/material/menu";
import {MatSelectModule} from "@angular/material/select";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDatePipe} from "@jsverse/transloco-locale";
import {DOCUMENT_ORIGIN_LABELS, TenantDocument} from "../../../models/document.model";
import {TenantScope} from "../../../models/tenant.model";
import * as DocumentActions from "../../../store/document/document.actions";
import * as DocumentSelectors from "../../../store/document/document.selectors";
import * as AreaActions from "../../../store/knowledge-area/knowledge-area.actions";
import * as AreaSelectors from "../../../store/knowledge-area/knowledge-area.selectors";
import * as SourceSelectors from "../../../store/knowledge-source/knowledge-source.selectors";
import {
    AreaChoice,
    areaChoiceLabel,
    areaLabel,
    AreaText,
    COMPANY_KNOWLEDGE_KEY,
    deleteUploadsArea,
    inAreaChoice,
    uploadAreaId,
} from "../../../utils/knowledge-area.util";
import {
    canRetry,
    DOCUMENT_STATUS_CHIPS,
    DocumentFilter,
    documentPath,
    documentProgress,
    fileTypeLabel,
    filterDocuments,
    formatBytes,
    MAX_ARCHIVE_BYTES,
    MAX_UPLOAD_BYTES,
    sortByPath,
    splitBySize,
} from "../../../utils/document.util";
import {clampPage, PAGE_SIZES, pageCount, pageOf, pageRange, PageSize} from "../../../utils/paging.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {DialogSave} from "../dialog-save";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {ArchiveImportDialogComponent, ArchiveImportDialogData} from "./archive-import-dialog.component";
import {KnowledgeAreasDialogComponent, KnowledgeAreasDialogData} from "./knowledge-areas-dialog.component";
import {MoveDocumentDialogComponent, MoveDocumentDialogData} from "./move-document-dialog.component";
import {injectLocale} from "../../../i18n/locale";

/**
 * A tenant's company knowledge and its areas with upload (single files or a ZIP archive with folders), retry, move to
 * another area and delete - one by one, or every upload of an area before its ZIP is imported anew: the tenant admin's
 * page "Firmenwissen" or the admin's tab of the same name. Reloads the list while a document is being embedded.
 */
@Component({
    selector: "app-document-panel",
    imports: [
        TranslocoPipe,
        TranslocoDatePipe,
        MatTableModule,
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatSelectModule,
        MatProgressBarModule,
        MatTooltipModule,
        EmptyStateComponent,
    ],
    template: `
        <div class="app-panel">
            <!-- Search, filters and upload stay in view while the page scrolls, the column headers right below. -->
            <div class="sticky-head" #stickyHead>
                <div class="app-panel-head">
                    <div class="app-search">
                        <mat-icon>search</mat-icon>
                        <input
                            type="search"
                            [placeholder]="'documents.panel.searchPlaceholder' | transloco"
                            [attr.aria-label]="'documents.panel.searchLabel' | transloco"
                            [value]="query()"
                            (input)="search($any($event.target).value)"
                        />
                    </div>
                    <mat-select
                        class="area-choice"
                        [value]="areaChoice()"
                        (valueChange)="chooseArea($event)"
                        [attr.aria-label]="'documents.panel.area' | transloco"
                    >
                        <mat-option value="all">{{ "knowledgeAreas.all" | transloco }}</mat-option>
                        <mat-option value="company">{{ companyKnowledge | transloco }}</mat-option>
                        @for (area of areas(); track area.id) {
                            <mat-option [value]="area.id">{{ area.name }}</mat-option>
                        }
                    </mat-select>
                    <div class="app-filters">
                        @for (option of filters; track option.value) {
                            <button
                                type="button"
                                class="app-filter"
                                [attr.aria-pressed]="filter() === option.value"
                                (click)="chooseFilter(option.value)"
                            >
                                {{ option.label | transloco }} <b>{{ count(option.value) }}</b>
                            </button>
                        }
                    </div>
                    <span class="spacer"></span>
                    <button mat-icon-button (click)="refresh()" [matTooltip]="'documents.panel.refresh' | transloco">
                        <mat-icon>refresh</mat-icon>
                    </button>
                    <input
                        #archiveInput
                        type="file"
                        accept=".zip,application/zip"
                        hidden
                        (change)="onArchiveChosen(archiveInput)"
                    />
                    <!-- With "Alle Bereiche" the target is asked for (targetMenu) - never the company knowledge unasked. -->
                    <button
                        mat-stroked-button
                        (click)="choose(archiveInput)"
                        [matMenuTriggerFor]="needsTarget() ? targetMenu : null"
                        [matMenuTriggerData]="{input: archiveInput}"
                        [disabled]="!uploadsOpen() || importing()"
                        [matTooltip]="
                            needsTarget()
                                ? ('documents.panel.importTooltip' | transloco: {max: maxArchiveSize()})
                                : ('documents.panel.importTooltipInto'
                                  | transloco
                                      : {
                                            max: maxArchiveSize(),
                                            area: (uploadArea().key | transloco: uploadArea().params),
                                        })
                        "
                    >
                        <mat-icon>folder_zip</mat-icon>
                        {{ "documents.panel.importZip" | transloco }}
                    </button>
                    <input #fileInput type="file" multiple hidden (change)="onFilesChosen(fileInput)" />
                    <button
                        mat-flat-button
                        (click)="choose(fileInput)"
                        [matMenuTriggerFor]="needsTarget() ? targetMenu : null"
                        [matMenuTriggerData]="{input: fileInput}"
                        [disabled]="!uploadsOpen()"
                        [matTooltip]="
                            needsTarget()
                                ? ('documents.panel.uploadTooltip' | transloco: {max: maxUploadSize()})
                                : ('documents.panel.uploadTooltipInto'
                                  | transloco
                                      : {
                                            max: maxUploadSize(),
                                            area: (uploadArea().key | transloco: uploadArea().params),
                                        })
                        "
                    >
                        <mat-icon>upload_file</mat-icon>
                        {{ "documents.panel.upload" | transloco }}
                    </button>
                    <button
                        mat-icon-button
                        [matMenuTriggerFor]="panelMenu"
                        [attr.aria-label]="'common.more' | transloco"
                    >
                        <mat-icon>more_vert</mat-icon>
                    </button>
                </div>

                @if (progress(); as p) {
                    @if (p.total > 0 || uploading() || importing()) {
                        <div class="app-summary">
                            <span
                                ><b>{{ p.total }}</b> {{ "documents.panel.summary.documents" | transloco }}</span
                            >
                            <span
                                ><b>{{ chunkCount() }}</b> {{ "documents.panel.summary.chunks" | transloco }}</span
                            >
                            @if (p.open > 0) {
                                <span
                                    ><b>{{ p.open }}</b> {{ "documents.panel.summary.open" | transloco }}</span
                                >
                            }
                            @if (uploading(); as count) {
                                <span>{{
                                    (count === 1
                                        ? "documents.panel.summary.uploading.one"
                                        : "documents.panel.summary.uploading.other"
                                    ) | transloco: {count}
                                }}</span>
                            }
                            @if (importing()) {
                                <span>{{ "documents.panel.summary.importing" | transloco }}</span>
                            }
                        </div>
                    }
                    @if (p.open > 0) {
                        <mat-progress-bar mode="determinate" [value]="(100 * (p.total - p.open)) / p.total" />
                    }
                }
                @if (waiting() > 0) {
                    <p class="app-note waiting">
                        <mat-icon>hourglass_top</mat-icon>
                        <span>{{
                            (waiting() === 1 ? "documents.panel.waiting.one" : "documents.panel.waiting.other")
                                | transloco: {count: waiting()}
                        }}</span>
                    </p>
                }
                @if (loading() || uploading() || importing()) {
                    <mat-progress-bar mode="indeterminate" />
                }
            </div>

            @if (unsorted().length === 0 && !loading()) {
                <app-empty-state
                    icon="menu_book"
                    [title]="
                        areaChoice() === 'all'
                            ? ('documents.panel.empty.title' | transloco)
                            : ('documents.panel.empty.titleIn'
                              | transloco: {area: (choiceLabel().key | transloco: choiceLabel().params)})
                    "
                    [text]="emptyText() | transloco"
                >
                    <button
                        mat-flat-button
                        (click)="choose(fileInput)"
                        [matMenuTriggerFor]="needsTarget() ? targetMenu : null"
                        [matMenuTriggerData]="{input: fileInput}"
                        [disabled]="!uploadsOpen()"
                    >
                        <mat-icon>upload_file</mat-icon>
                        {{ "documents.panel.upload" | transloco }}
                    </button>
                </app-empty-state>
            } @else {
                <!-- No scroll area of its own: the page scrolls, and the column headers stick below the top bar. -->
                <div class="table-wrap">
                    <table mat-table [dataSource]="pageRows()" [trackBy]="trackById" class="document-table">
                        <ng-container matColumnDef="fileName">
                            <th mat-header-cell *matHeaderCellDef>
                                {{ "documents.panel.column.document" | transloco }}
                            </th>
                            <td mat-cell *matCellDef="let document">
                                <div class="cell-main">
                                    <span class="app-filetype">{{
                                        fileType(document.fileName) ?? ("documents.fileTypeNone" | transloco)
                                    }}</span>
                                    <div class="name">
                                        <div class="cell-title">{{ document.fileName }}</div>
                                        @if (document.folder) {
                                            <div class="cell-sub">{{ document.folder }}</div>
                                        }
                                    </div>
                                </div>
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="status">
                            <th mat-header-cell *matHeaderCellDef>{{ "documents.panel.column.status" | transloco }}</th>
                            <td mat-cell *matCellDef="let document">
                                <span [class]="'app-chip ' + statusChips[document.status].tone">
                                    {{ statusChips[document.status].label | transloco }}
                                </span>
                                @if (document.status === "FAILED" && document.error) {
                                    <div class="error">{{ document.error }}</div>
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="origin">
                            <th mat-header-cell *matHeaderCellDef>{{ "documents.panel.column.origin" | transloco }}</th>
                            <td mat-cell *matCellDef="let document">
                                <span
                                    class="app-tag"
                                    [matTooltip]="
                                        document.externalId
                                            ? ('documents.panel.externalId' | transloco: {id: document.externalId})
                                            : ''
                                    "
                                >
                                    @let source = document.sourceId === null ? null : sourceNames()[document.sourceId];
                                    {{
                                        source
                                            ? ("documents.origin.sourceNamed" | transloco: {name: source})
                                            : (originLabels[document.origin] | transloco)
                                    }}
                                </span>
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="area">
                            <th mat-header-cell *matHeaderCellDef>{{ "documents.panel.column.area" | transloco }}</th>
                            <td mat-cell *matCellDef="let document">
                                <span class="app-tag" [class.company]="document.areaId === null">
                                    @let area = areaOf(document);
                                    {{ area.key | transloco: area.params }}
                                </span>
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="chunkCount">
                            <th mat-header-cell *matHeaderCellDef class="right">
                                {{ "documents.panel.column.chunks" | transloco }}
                            </th>
                            <td mat-cell *matCellDef="let document" class="right num">
                                {{ document.status === "READY" ? document.chunkCount : "–" }}
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="size">
                            <th mat-header-cell *matHeaderCellDef class="right">
                                {{ "documents.panel.column.size" | transloco }}
                            </th>
                            <td mat-cell *matCellDef="let document" class="right num">
                                {{ size(document.sizeBytes) }}
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="createdAt">
                            <th mat-header-cell *matHeaderCellDef>{{ "documents.panel.column.added" | transloco }}</th>
                            <td mat-cell *matCellDef="let document" class="num muted">
                                {{ document.createdAt | translocoDate: {dateStyle: "medium", timeStyle: "short"} }}
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="actions">
                            <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                            <td mat-cell *matCellDef="let document" class="actions-column">
                                <button
                                    mat-icon-button
                                    [matMenuTriggerFor]="rowMenu"
                                    [matMenuTriggerData]="{document}"
                                    [attr.aria-label]="'common.more' | transloco"
                                >
                                    <mat-icon>more_vert</mat-icon>
                                </button>
                            </td>
                        </ng-container>
                        <tr mat-header-row *matHeaderRowDef="displayedColumns; sticky: true"></tr>
                        <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
                        <tr class="mat-row" *matNoDataRow>
                            <td class="mat-cell no-data" [attr.colspan]="displayedColumns.length">
                                {{ "documents.panel.noMatch" | transloco }}
                            </td>
                        </tr>
                    </table>
                </div>
                <div class="pager">
                    <span class="muted">{{ "documents.panel.perPage" | transloco }}</span>
                    <mat-select
                        class="page-size"
                        [value]="pageSize()"
                        (valueChange)="choosePageSize($event)"
                        [attr.aria-label]="'documents.panel.perPageLabel' | transloco"
                    >
                        @for (option of pageSizes; track option.value) {
                            <mat-option [value]="option.value">{{ option.label | transloco }}</mat-option>
                        }
                    </mat-select>
                    <span class="num range">{{ range().key | transloco: range().params }}</span>
                    <button
                        mat-icon-button
                        (click)="pageIndex.set(shownPage() - 1)"
                        [disabled]="shownPage() === 0"
                        [attr.aria-label]="'documents.panel.previousPage' | transloco"
                    >
                        <mat-icon>chevron_left</mat-icon>
                    </button>
                    <button
                        mat-icon-button
                        (click)="pageIndex.set(shownPage() + 1)"
                        [disabled]="shownPage() >= pages() - 1"
                        [attr.aria-label]="'documents.panel.nextPage' | transloco"
                    >
                        <mat-icon>chevron_right</mat-icon>
                    </button>
                </div>
            }
        </div>

        <mat-menu #targetMenu="matMenu">
            <ng-template matMenuContent let-input="input">
                <div class="menu-title">{{ "documents.panel.uploadInto" | transloco }}</div>
                <button mat-menu-item (click)="uploadInto(input, null)">
                    <mat-icon>public</mat-icon>
                    <span>{{ "knowledgeAreas.companyKnowledgeForAll" | transloco }}</span>
                </button>
                @for (area of areas(); track area.id) {
                    <button mat-menu-item (click)="uploadInto(input, area.id)">
                        <mat-icon>folder_shared</mat-icon>
                        <span>{{ area.name }}</span>
                    </button>
                }
            </ng-template>
        </mat-menu>

        <mat-menu #panelMenu="matMenu" xPosition="before">
            <button mat-menu-item (click)="openAreas()">
                <mat-icon>folder_shared</mat-icon>
                <span>{{ "documents.panel.manageAreas" | transloco }}</span>
            </button>
            <button mat-menu-item (click)="confirmEmbedAllAgain()" [disabled]="!canUpload() || documentCount() === 0">
                <mat-icon>autorenew</mat-icon>
                <span>{{ "documents.panel.embedAllAgain" | transloco }}</span>
            </button>
            <button
                mat-menu-item
                class="danger"
                (click)="confirmDeleteUploads()"
                [disabled]="areaChoice() === 'all' || uploadedInChoice() === 0"
            >
                <mat-icon>delete_sweep</mat-icon>
                <span>
                    @if (areaChoice() === "all") {
                        {{ "documents.panel.deleteUploadsChooseArea" | transloco }}
                    } @else {
                        {{
                            "documents.panel.deleteUploadsIn"
                                | transloco: {area: (choiceLabel().key | transloco: choiceLabel().params)}
                        }}
                    }
                </span>
            </button>
        </mat-menu>

        <mat-menu #rowMenu="matMenu" xPosition="before">
            <ng-template matMenuContent let-document="document">
                @if (canRetry(document)) {
                    <button mat-menu-item (click)="retry(document)">
                        <mat-icon>replay</mat-icon>
                        <span>{{ "documents.panel.retry" | transloco }}</span>
                    </button>
                }
                <button mat-menu-item (click)="move(document)">
                    <mat-icon>drive_file_move</mat-icon>
                    <span>{{ "documents.panel.move" | transloco }}</span>
                </button>
                <button mat-menu-item class="danger" (click)="confirmDelete(document)">
                    <mat-icon>delete</mat-icon>
                    <span>{{ "common.delete" | transloco }}</span>
                </button>
            </ng-template>
        </mat-menu>
    `,
    styles: [
        `
            /* A wide table scrolls sideways on a narrow screen; above, no scroll area may hold the sticky headers. */
            @media (max-width: 960px) {
                .table-wrap {
                    overflow-x: auto;
                }
            }

            .waiting {
                margin: 8px 14px;
            }

            .sticky-head {
                position: sticky;
                top: 0;
                z-index: 3;
                background: var(--app-surface);
                border-radius: 10px 10px 0 0;
            }

            /* Below the sticky head - its height changes when the toolbar wraps (--sticky-head, set in the class). */
            .document-table .mat-mdc-header-cell {
                top: var(--sticky-head, 0px) !important;
            }

            .pager {
                display: flex;
                align-items: center;
                justify-content: flex-end;
                gap: 8px;
                padding: 6px 8px 6px 14px;
                border-top: 1px solid var(--app-border);
                font-size: 13px;
            }

            .page-size {
                width: 72px;
            }

            .area-choice {
                width: 180px;
            }

            .menu-title {
                padding: 8px 16px 4px;
                font-size: 12px;
                color: var(--app-text-3);
            }

            .app-tag.company {
                color: var(--app-text-3);
            }

            .range {
                margin: 0 8px 0 16px;
                color: var(--app-text-2);
            }

            .document-table {
                width: 100%;
            }

            .mat-column-fileName {
                min-width: 240px;
            }

            .name {
                min-width: 0;
                word-break: break-word;
            }

            .right {
                text-align: right;
            }

            .error {
                font-size: 12px;
                color: var(--app-err);
                margin-top: 4px;
                max-width: 36ch;
            }

            .no-data {
                padding: 16px;
                color: var(--app-text-3);
            }

            .danger,
            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class DocumentPanelComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private destroyRef = inject(DestroyRef);
    private transloco = inject(TranslocoService);
    private readonly locale = injectLocale();

    readonly scope = input.required<TenantScope>();
    /** False while uploading makes no sense (no embedding model, own documents switched off). */
    readonly canUpload = input(true);

    readonly all = this.store.selectSignal(DocumentSelectors.selectDocuments);
    /** Every document of the tenant, whatever is filtered - what "embed all again" takes. */
    readonly documentCount = computed(() => this.all().length);
    readonly areas = this.store.selectSignal(AreaSelectors.selectAreas);
    /** The knowledge sources' names by id - where a document of origin SOURCE comes from. */
    readonly sourceNames = this.store.selectSignal(SourceSelectors.selectSourceNames);
    /**
     * The tenant's documents waiting to be embedded, in every area - they are embedded one after the other, so an
     * upload during a large import waits behind it; the note says why.
     */
    readonly waiting = computed(
        () => this.all().filter(document => document.status === "PENDING" || document.status === "PROCESSING").length,
    );
    /**
     * Uploads wait for the areas: without them needsTarget is false, and a file meant for an area would go into the
     * company knowledge, found by everyone.
     */
    readonly uploadsOpen = computed(() => this.canUpload() && this.areasLoaded());
    private readonly areasLoaded = this.store.selectSignal(AreaSelectors.selectAreasLoaded);
    /** Which documents are listed - and where uploads and ZIP imports go (with "all": the company knowledge). */
    readonly areaChoice = signal<AreaChoice>("all");
    readonly choiceLabel = computed(() => areaChoiceLabel(this.areaChoice(), this.areas()));
    /** With "Alle Bereiche" and areas to choose from, an upload asks where it goes. */
    readonly needsTarget = computed(() => this.areaChoice() === "all" && this.areas().length > 0);
    /** Where an upload goes without asking (needsTarget false) - named in the upload buttons' tooltips. */
    readonly uploadArea = computed(() => areaLabel(uploadAreaId(this.areaChoice()), this.areas()));
    /** The area the files being chosen go into - set by the target menu, else the area choice's. */
    private target: number | null = null;
    /** The documents of the chosen area - what the filters, the search and the list work on. */
    readonly unsorted = computed(() => this.all().filter(document => inAreaChoice(document.areaId, this.areaChoice())));
    /** The uploads "delete all uploaded" would delete - the client software's documents stay. */
    readonly uploadedInChoice = computed(() => this.unsorted().filter(document => document.origin === "UPLOAD").length);
    readonly companyKnowledge = COMPANY_KNOWLEDGE_KEY;
    readonly query = signal("");
    readonly filter = signal<DocumentFilter>("all");
    readonly filters: {value: DocumentFilter; label: string}[] = [
        {value: "all", label: "documents.panel.filter.all"},
        {value: "ready", label: "documents.panel.filter.ready"},
        {value: "open", label: "documents.panel.filter.open"},
        {value: "failed", label: "documents.panel.filter.failed"},
    ];
    /** Like a file tree: by folder, then name - those of the filter and search. */
    readonly documents = computed(() => sortByPath(filterDocuments(this.unsorted(), this.query(), this.filter())));
    readonly pageSizes = PAGE_SIZES;
    readonly pageSize = signal<PageSize>(50);
    readonly pageIndex = signal(0);
    /** The page asked for, within the pages there are - a search or a deletion can shrink the list below it. */
    readonly shownPage = computed(() => clampPage(this.pageIndex(), this.documents().length, this.pageSize()));
    readonly pages = computed(() => pageCount(this.documents().length, this.pageSize()));
    readonly pageRows = computed(() => pageOf(this.documents(), this.shownPage(), this.pageSize()));
    readonly range = computed(() => pageRange(this.shownPage(), this.documents().length, this.pageSize()));
    /** Of the chosen area, like the list below - the head and the list never count different documents. */
    readonly chunkCount = computed(() => this.unsorted().reduce((sum, document) => sum + document.chunkCount, 0));
    /** Ready, still open (waiting or being embedded) and failed - followed while the list is polled. */
    readonly progress = computed(() => documentProgress(this.unsorted()));
    readonly emptyText = computed(() =>
        typeof this.areaChoice() === "number" ? "documents.panel.empty.textArea" : "documents.panel.empty.text",
    );
    readonly loading = this.store.selectSignal(DocumentSelectors.selectDocumentsLoading);
    readonly uploading = this.store.selectSignal(DocumentSelectors.selectUploading);
    readonly importing = this.store.selectSignal(DocumentSelectors.selectImporting);
    readonly canRetry = canRetry;

    // Keyed by string: table rows (matCellDef) are untyped in the template.
    readonly statusChips: Record<string, {label: string; tone: string}> = DOCUMENT_STATUS_CHIPS;
    readonly originLabels: Record<string, string> = DOCUMENT_ORIGIN_LABELS;
    readonly maxUploadSize = computed(() => formatBytes(MAX_UPLOAD_BYTES, this.locale()));
    readonly maxArchiveSize = computed(() => formatBytes(MAX_ARCHIVE_BYTES, this.locale()));
    readonly displayedColumns = ["fileName", "status", "area", "origin", "chunkCount", "size", "createdAt", "actions"];
    readonly trackById = (_index: number, document: TenantDocument) => document.id;

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly stickyHead = viewChild.required<ElementRef<HTMLElement>>("stickyHead");

    constructor() {
        // The column headers stick right below the head, whose height depends on how often the toolbar wraps.
        afterNextRender(() => {
            const head = this.stickyHead().nativeElement;
            const observer = new ResizeObserver(() =>
                this.host.nativeElement.style.setProperty("--sticky-head", `${head.offsetHeight}px`),
            );
            observer.observe(head);
            this.destroyRef.onDestroy(() => observer.disconnect());
        });
        // The polling effect keeps running until it sees its explicit stop action.
        this.destroyRef.onDestroy(() => this.store.dispatch(DocumentActions.stopDocumentPolling()));

        this.actions$
            .pipe(ofType(DocumentActions.uploadDocumentFailure), takeUntilDestroyed())
            .subscribe(({fileName, error}) =>
                this.showError(this.transloco.translate("documents.snack.uploadFailed", {name: fileName, error})),
            );
        this.actions$
            // Only this tenant's import - another's may finish while this panel is open.
            .pipe(
                ofType(DocumentActions.importArchiveSuccess),
                filter(({scope}) => scope === this.scope()),
                takeUntilDestroyed(),
            )
            .subscribe(({fileName, files}) =>
                this.dialog.open<ArchiveImportDialogComponent, ArchiveImportDialogData>(ArchiveImportDialogComponent, {
                    width: "760px",
                    maxHeight: "85vh",
                    data: {fileName, files},
                }),
            );
        this.actions$
            .pipe(ofType(DocumentActions.importArchiveFailure), takeUntilDestroyed())
            .subscribe(({fileName, error}) =>
                this.showError(this.transloco.translate("documents.snack.importFailed", {name: fileName, error})),
            );
        this.notifyOn(DocumentActions.retryDocumentSuccess, () => this.text("documents.snack.retried"));
        this.notifyOn(DocumentActions.deleteDocumentSuccess, () => this.text("documents.snack.deleted"));
        this.notifyOn(DocumentActions.moveDocumentSuccess, ({document}) =>
            this.text("documents.snack.moved", {area: this.areaText(this.areaOf(document))}),
        );
        this.notifyOn(DocumentActions.embedAllAgainSuccess, ({documents}) =>
            this.text(documents === 1 ? "documents.snack.embeddingAgain.one" : "documents.snack.embeddingAgain.other", {
                count: documents,
            }),
        );
        // The documents wait for their run now: the list shows it, and the polling follows it.
        this.actions$
            .pipe(ofType(DocumentActions.embedAllAgainSuccess), takeUntilDestroyed())
            .subscribe(({scope}) => this.store.dispatch(DocumentActions.pollDocuments({scope})));
        this.notifyOn(
            DocumentActions.embedAllAgainFailure,
            ({error}) => this.text("documents.snack.failed", {error}),
            true,
        );
        this.notifyOn(DocumentActions.deleteUploadsSuccess, ({label, deleted}) =>
            this.text(deleted === 1 ? "documents.snack.uploadsDeleted.one" : "documents.snack.uploadsDeleted.other", {
                count: deleted,
                area: label,
            }),
        );
        this.notifyOn(AreaActions.createAreaSuccess, ({area}) =>
            this.text("knowledgeAreas.snack.created", {name: area.name}),
        );
        this.notifyOn(AreaActions.deleteAreaSuccess, () => this.text("knowledgeAreas.snack.deleted"));
        // A deleted area cannot stay chosen - uploads into it would be refused.
        this.actions$.pipe(ofType(AreaActions.deleteAreaSuccess), takeUntilDestroyed()).subscribe(({id}) => {
            if (this.areaChoice() === id) {
                this.chooseArea("all");
            }
        });
        this.notifyOn(
            DocumentActions.moveDocumentFailure,
            ({error}) => this.text("documents.snack.moveFailed", {error}),
            true,
        );
        this.notifyOn(
            DocumentActions.deleteUploadsFailure,
            ({error}) => this.text("documents.snack.deleteFailed", {error}),
            true,
        );
        this.notifyOn(AreaActions.createAreaFailure, ({error}) => this.text("documents.snack.failed", {error}), true);
        this.notifyOn(AreaActions.renameAreaFailure, ({error}) => this.text("documents.snack.failed", {error}), true);
        this.notifyOn(AreaActions.deleteAreaFailure, ({error}) => this.text("documents.snack.failed", {error}), true);
        this.notifyOn(
            DocumentActions.retryDocumentFailure,
            ({error}) => this.text("documents.snack.failed", {error}),
            true,
        );
        this.notifyOn(
            DocumentActions.deleteDocumentFailure,
            ({error}) => this.text("documents.snack.deleteFailed", {error}),
            true,
        );
    }

    ngOnInit(): void {
        this.store.dispatch(AreaActions.loadAreas({scope: this.scope()}));
        this.store.dispatch(DocumentActions.loadDocuments({scope: this.scope()}));
        this.store.dispatch(DocumentActions.startDocumentPolling({scope: this.scope()}));
    }

    size(bytes: number): string {
        return formatBytes(bytes, this.locale());
    }

    fileType(fileName: string): string | null {
        return fileTypeLabel(fileName);
    }

    /** Another search, filter or page size starts at the first page. */
    search(text: string): void {
        this.query.set(text);
        this.pageIndex.set(0);
    }

    chooseArea(choice: AreaChoice): void {
        this.areaChoice.set(choice);
        this.pageIndex.set(0);
    }

    areaOf(document: TenantDocument): AreaText {
        return areaLabel(document.areaId, this.areas());
    }

    chooseFilter(filter: DocumentFilter): void {
        this.filter.set(filter);
        this.pageIndex.set(0);
    }

    choosePageSize(size: PageSize): void {
        this.pageSize.set(size);
        this.pageIndex.set(0);
    }

    /** The number on a filter button - of all documents, whatever the search. */
    count(filter: DocumentFilter): number {
        return filterDocuments(this.unsorted(), "", filter).length;
    }

    refresh(): void {
        this.store.dispatch(AreaActions.loadAreas({scope: this.scope()}));
        this.store.dispatch(DocumentActions.loadDocuments({scope: this.scope()}));
    }

    /** Opens the file dialog at once - unless the target menu asks first (needsTarget). */
    choose(input: HTMLInputElement): void {
        if (!this.needsTarget()) {
            this.target = uploadAreaId(this.areaChoice());
            input.click();
        }
    }

    uploadInto(input: HTMLInputElement, areaId: number | null): void {
        this.target = areaId;
        input.click();
    }

    onFilesChosen(input: HTMLInputElement): void {
        const {accepted, tooLarge} = splitBySize(Array.from(input.files ?? []));
        // Cleared so choosing the same file again fires (change) again.
        input.value = "";
        if (tooLarge.length > 0) {
            this.showError(
                this.text("documents.snack.tooLarge", {
                    max: this.maxUploadSize(),
                    files: tooLarge.map(file => file.name).join(", "),
                }),
            );
        }
        if (accepted.length > 0) {
            this.store.dispatch(
                DocumentActions.uploadDocuments({
                    scope: this.scope(),
                    files: accepted,
                    areaId: this.target,
                }),
            );
        }
    }

    onArchiveChosen(input: HTMLInputElement): void {
        const file = input.files?.[0];
        input.value = "";
        if (!file) {
            return;
        }
        if (file.size > MAX_ARCHIVE_BYTES) {
            this.showError(this.text("documents.snack.archiveTooLarge", {max: this.maxArchiveSize()}));
            return;
        }
        this.store.dispatch(DocumentActions.importArchive({scope: this.scope(), file, areaId: this.target}));
    }

    retry(document: TenantDocument): void {
        this.store.dispatch(DocumentActions.retryDocument({scope: this.scope(), id: document.id}));
    }

    openAreas(): void {
        this.dialog.open<KnowledgeAreasDialogComponent, KnowledgeAreasDialogData>(KnowledgeAreasDialogComponent, {
            width: "560px",
            data: {scope: this.scope()},
        });
    }

    move(document: TenantDocument): void {
        const scope = this.scope();
        this.dialogSave.open<MoveDocumentDialogComponent, MoveDocumentDialogData, number | null>(
            MoveDocumentDialogComponent,
            {width: "520px", data: {path: documentPath(document), areaId: document.areaId, areas: this.areas()}},
            {
                action: areaId => DocumentActions.moveDocument({scope, id: document.id, areaId}),
                success: [DocumentActions.moveDocumentSuccess],
                failure: DocumentActions.moveDocumentFailure,
            },
        );
    }

    /** Costs as much as uploading every document anew, and they are not found until each is embedded again. */
    confirmEmbedAllAgain(): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "480px",
                data: {
                    title: this.text("documents.confirm.embedAllAgainTitle"),
                    message: this.text("documents.confirm.embedAllAgainMessage", {count: this.documentCount()}),
                    confirmLabel: this.text("documents.confirm.embedAllAgainConfirm"),
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(DocumentActions.embedAllAgain({scope: this.scope()})));
    }

    /** Before a ZIP is imported anew: every upload of the chosen area goes, so files no longer in it do not stay. */
    confirmDeleteUploads(): void {
        const area = deleteUploadsArea(this.areaChoice());
        if (area === null) {
            return;
        }
        // Translated now: the snackbar after the deletion names the area as it was asked for.
        const label = this.areaText(this.choiceLabel());
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "480px",
                data: {
                    title: this.text("documents.confirm.deleteUploadsTitle", {area: label}),
                    message: this.text(
                        this.uploadedInChoice() === 1
                            ? "documents.confirm.deleteUploadsMessage.one"
                            : "documents.confirm.deleteUploadsMessage.other",
                        {count: this.uploadedInChoice()},
                    ),
                    confirmLabel: this.text("documents.confirm.deleteUploadsConfirm"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(DocumentActions.deleteUploads({scope: this.scope(), area, label})));
    }

    confirmDelete(document: TenantDocument): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.text(
                        document.origin === "SOURCE"
                            ? "documents.confirm.deleteFromSource"
                            : "documents.confirm.deleteMessage",
                        {path: documentPath(document)},
                    ),
                    confirmLabel: this.text("common.delete"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() =>
                this.store.dispatch(DocumentActions.deleteDocument({scope: this.scope(), id: document.id})),
            );
    }

    private text(key: string, params?: Record<string, unknown>): string {
        return this.transloco.translate(key, params);
    }

    private areaText(area: AreaText): string {
        return this.text(area.key, area.params);
    }

    private showError(message: string): void {
        this.snackBar.open(message, this.text("common.ok"), {duration: 10000, panelClass: "error-snackbar"});
    }

    /** Must be called from the constructor (injection context) - takeUntilDestroyed() relies on it. */
    private notifyOn<AC extends ActionCreator<string, Creator>>(
        action: AC,
        message: (payload: ReturnType<AC>) => string,
        isError = false,
    ): void {
        this.actions$.pipe(ofType(action), takeUntilDestroyed()).subscribe(payload =>
            this.snackBar.open(message(payload), this.text("common.ok"), {
                duration: isError ? 10000 : 3000,
                panelClass: isError ? "error-snackbar" : undefined,
            }),
        );
    }
}
