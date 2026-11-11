import {Component, DestroyRef, inject} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {TranslocoDatePipe} from "@jsverse/transloco-locale";
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
import {AccessAssignment, AccessRequest} from "../../models/access-request.model";
import * as AccessRequestActions from "../../store/access-request/access-request.actions";
import * as AccessRequestSelectors from "../../store/access-request/access-request.selectors";
import {ConfirmDialogComponent, ConfirmDialogData} from "../shared/confirm-dialog/confirm-dialog.component";
import {initials} from "../../utils/shell.util";
import {DialogSave} from "../shared/dialog-save";
import {EmptyStateComponent} from "../shared/empty-state/empty-state.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";
import {AccessAssignDialogComponent} from "./access-assign-dialog.component";

/** Admin page: the people the WAM proxy let through who are no tenant user yet - assigned to a tenant or rejected. */
@Component({
    selector: "app-access-request-list",
    imports: [
        TranslocoPipe,
        TranslocoDatePipe,
        MatTableModule,
        MatButtonModule,
        MatIconModule,
        MatProgressBarModule,
        MatTooltipModule,
        PageHeaderComponent,
        EmptyStateComponent,
    ],
    template: `
        <div class="page">
            <app-page-header
                [title]="'accessRequests.title' | transloco"
                [subtitle]="'accessRequests.subtitle' | transloco"
            />

            <div class="app-panel">
                <div class="app-panel-head">
                    <span class="app-panel-title">{{ "accessRequests.open" | transloco }}</span>
                    <span class="spacer"></span>
                    <button mat-icon-button (click)="refresh()" [matTooltip]="'accessRequests.refresh' | transloco">
                        <mat-icon>refresh</mat-icon>
                    </button>
                </div>

                @if (loading()) {
                    <mat-progress-bar mode="indeterminate" />
                }

                @if (requests().length === 0 && !loading()) {
                    <app-empty-state
                        icon="how_to_reg"
                        [title]="'accessRequests.empty.title' | transloco"
                        [text]="'accessRequests.empty.text' | transloco"
                    />
                } @else {
                    <div class="table-container">
                        <table mat-table [dataSource]="requests()" [trackBy]="trackById" class="request-table">
                            <ng-container matColumnDef="person">
                                <th mat-header-cell *matHeaderCellDef>
                                    {{ "accessRequests.column.person" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let request">
                                    <div class="cell-main">
                                        <span class="app-avatar">{{ initialsOf(request.displayName) }}</span>
                                        <div>
                                            <div class="cell-title">{{ request.displayName }}</div>
                                            <div class="cell-sub mono">{{ request.username }}</div>
                                        </div>
                                    </div>
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="requestedAt">
                                <th mat-header-cell *matHeaderCellDef>
                                    {{ "accessRequests.column.requested" | transloco }}
                                </th>
                                <td mat-cell *matCellDef="let request" class="num muted">
                                    {{ request.requestedAt | translocoDate: {dateStyle: "medium", timeStyle: "short"} }}
                                </td>
                            </ng-container>
                            <ng-container matColumnDef="actions">
                                <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                                <td mat-cell *matCellDef="let request" class="actions-column">
                                    <button
                                        mat-icon-button
                                        (click)="openAssignDialog(request)"
                                        [matTooltip]="'accessRequests.assignTooltip' | transloco"
                                    >
                                        <mat-icon>how_to_reg</mat-icon>
                                    </button>
                                    <button
                                        mat-icon-button
                                        color="warn"
                                        (click)="confirmReject(request)"
                                        [matTooltip]="'accessRequests.reject' | transloco"
                                    >
                                        <mat-icon>block</mat-icon>
                                    </button>
                                </td>
                            </ng-container>

                            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                            <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
                        </table>
                    </div>
                }
            </div>
        </div>
    `,
    styles: [
        `
            .request-table {
                width: 100%;
            }
        `,
    ],
})
export class AccessRequestListComponent {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);
    private destroyRef = inject(DestroyRef);

    readonly requests = this.store.selectSignal(AccessRequestSelectors.selectAccessRequests);
    readonly loading = this.store.selectSignal(AccessRequestSelectors.selectAccessRequestsLoading);

    readonly displayedColumns = ["person", "requestedAt", "actions"];
    readonly trackById = (_index: number, request: AccessRequest) => request.id;

    constructor() {
        this.store.dispatch(AccessRequestActions.loadAccessRequests());

        this.notifyOn(AccessRequestActions.assignAccessRequestSuccess, () =>
            this.translate("accessRequests.snack.assigned"),
        );
        this.notifyOn(AccessRequestActions.rejectAccessRequestSuccess, () =>
            this.translate("accessRequests.snack.rejected"),
        );
        this.notifyOn(
            AccessRequestActions.assignAccessRequestFailure,
            ({error}) => this.translate("accessRequests.snack.error", {error}),
            true,
        );
        this.notifyOn(
            AccessRequestActions.rejectAccessRequestFailure,
            ({error}) => this.translate("accessRequests.snack.error", {error}),
            true,
        );
    }

    initialsOf(name: string): string {
        return initials(name);
    }

    refresh(): void {
        this.store.dispatch(AccessRequestActions.loadAccessRequests());
    }

    openAssignDialog(request: AccessRequest): void {
        this.dialogSave.open<AccessAssignDialogComponent, AccessRequest, AccessAssignment>(
            AccessAssignDialogComponent,
            {width: "520px", maxWidth: "95vw", data: request},
            {
                action: assignment => AccessRequestActions.assignAccessRequest({id: request.id, assignment}),
                success: [AccessRequestActions.assignAccessRequestSuccess],
                failure: AccessRequestActions.assignAccessRequestFailure,
            },
        );
    }

    confirmReject(request: AccessRequest): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.translate("accessRequests.confirmReject", {name: request.displayName}),
                    confirmLabel: this.translate("accessRequests.reject"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(AccessRequestActions.rejectAccessRequest({id: request.id})));
    }

    /** Must be called from the constructor (injection context) - takeUntilDestroyed() relies on it. */
    private notifyOn<AC extends ActionCreator<string, Creator>>(
        action: AC,
        message: (payload: ReturnType<AC>) => string,
        isError = false,
    ): void {
        this.actions$.pipe(ofType(action), takeUntilDestroyed()).subscribe(payload =>
            this.snackBar.open(message(payload), this.translate("common.ok"), {
                duration: isError ? 10000 : 3000,
                panelClass: isError ? "error-snackbar" : undefined,
            }),
        );
    }

    private translate(key: string, params?: Record<string, unknown>): string {
        return this.transloco.translate(key, params);
    }
}
