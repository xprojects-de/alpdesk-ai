import * as AuthSelectors from "../../../store/auth/auth.selectors";
import {
    filterTenantUsers,
    languageLabel,
    tenantUserStatus,
    usersCreatedByAdminOnly,
} from "../../../utils/tenant-user.util";
import {initials} from "../../../utils/shell.util";
import {Component, computed, DestroyRef, inject, input, OnInit, signal} from "@angular/core";
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
import {TenantUser, TenantUserRequest, UserFromTenantRequest} from "../../../models/tenant-user.model";
import * as TenantUserActions from "../../../store/tenant-user/tenant-user.actions";
import * as TenantUserSelectors from "../../../store/tenant-user/tenant-user.selectors";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import * as GroupActions from "../../../store/user-group/user-group.actions";
import * as GroupSelectors from "../../../store/user-group/user-group.selectors";
import {fromIdm, groupNames, GroupText} from "../../../utils/user-group.util";
import {DialogSave, DialogSaveSpec} from "../dialog-save";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {TenantUserDialogComponent, TenantUserDialogData} from "./tenant-user-dialog.component";

/**
 * Lists, creates, edits and deletes one tenant's users - the employees who log in to the tenant area: the tenant admin's
 * page "Benutzer" (scope "self") or the admin's tab of the tenant (a tenant's id). It loads the tenant's groups too -
 * the groups panel below it shows them.
 */
import {UserFromTenantDialogComponent, UserFromTenantDialogData} from "./user-from-tenant-dialog.component";
@Component({
    selector: "app-tenant-users-panel",
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
                <div class="app-search">
                    <mat-icon>search</mat-icon>
                    <input
                        type="search"
                        [placeholder]="'users.list.searchPlaceholder' | transloco"
                        [attr.aria-label]="'users.list.searchLabel' | transloco"
                        [value]="query()"
                        (input)="query.set($any($event.target).value)"
                    />
                </div>
                <span class="spacer"></span>
                @if (byAdmin()) {
                    <button mat-stroked-button (click)="openFromTenantDialog()">
                        <mat-icon>group_add</mat-icon>
                        {{ "users.list.fromTenant" | transloco }}
                    </button>
                }
                @if (!createdByAdminOnly()) {
                    <button mat-flat-button (click)="openCreateDialog()">
                        <mat-icon>person_add</mat-icon>
                        {{ "users.list.create" | transloco }}
                    </button>
                }
            </div>

            @if (createdByAdminOnly()) {
                <p class="app-note">
                    <mat-icon>info</mat-icon>
                    {{ "users.list.wamNote" | transloco }}
                </p>
            }

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (unfiltered().length === 0 && !loading()) {
                <app-empty-state
                    icon="group"
                    [title]="'users.list.empty.title' | transloco"
                    [text]="'users.list.empty.text' | transloco"
                >
                    @if (!createdByAdminOnly()) {
                        <button mat-flat-button (click)="openCreateDialog()">
                            <mat-icon>person_add</mat-icon>
                            {{ "users.list.create" | transloco }}
                        </button>
                    }
                </app-empty-state>
            } @else {
                <div class="table-container">
                    <table mat-table [dataSource]="users()" [trackBy]="trackById" class="users-table">
                        <ng-container matColumnDef="user">
                            <th mat-header-cell *matHeaderCellDef>{{ "users.list.column.user" | transloco }}</th>
                            <td mat-cell *matCellDef="let user">
                                <div class="cell-main">
                                    <span class="app-avatar">{{ initialsOf(user.displayName) }}</span>
                                    <div>
                                        <div class="cell-title">{{ user.displayName }}</div>
                                        <div class="cell-sub">{{ user.username }}</div>
                                    </div>
                                </div>
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="admin">
                            <th mat-header-cell *matHeaderCellDef>{{ "users.list.column.role" | transloco }}</th>
                            <td mat-cell *matCellDef="let user">
                                @if (user.admin) {
                                    <span class="app-chip plain info">{{ "users.list.roleAdmin" | transloco }}</span>
                                } @else {
                                    <span class="muted">{{ "users.list.roleUser" | transloco }}</span>
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="groups">
                            <th mat-header-cell *matHeaderCellDef>{{ "users.list.column.groups" | transloco }}</th>
                            <td mat-cell *matCellDef="let user" class="muted">
                                @let groupText = groupsOf(user);
                                {{ groupText.key | transloco: groupText.params }}
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="language">
                            <th mat-header-cell *matHeaderCellDef>{{ "users.list.column.language" | transloco }}</th>
                            <td mat-cell *matCellDef="let user">
                                <span class="app-tag">{{ languageOf(user) }}</span>
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="active">
                            <th mat-header-cell *matHeaderCellDef>{{ "users.list.column.status" | transloco }}</th>
                            <td mat-cell *matCellDef="let user">
                                @let chip = status(user);
                                <span [class]="'app-chip ' + chip.tone">{{ chip.label | transloco }}</span>
                                @if (isFromIdm(user)) {
                                    <span class="app-chip plain info">{{ "users.status.fromIdm" | transloco }}</span>
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="lastLoginAt">
                            <th mat-header-cell *matHeaderCellDef>{{ "users.list.column.lastLogin" | transloco }}</th>
                            <td mat-cell *matCellDef="let user" class="num muted">
                                @if (user.lastLoginAt) {
                                    {{ user.lastLoginAt | translocoDate: {dateStyle: "medium", timeStyle: "short"} }}
                                } @else {
                                    {{ "users.list.never" | transloco }}
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="actions">
                            <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                            <td mat-cell *matCellDef="let user" class="actions-column">
                                <button
                                    mat-icon-button
                                    (click)="openEditDialog(user)"
                                    [matTooltip]="'common.edit' | transloco"
                                >
                                    <mat-icon>edit</mat-icon>
                                </button>
                                <button
                                    mat-icon-button
                                    class="danger"
                                    (click)="confirmDelete(user)"
                                    [matTooltip]="'common.delete' | transloco"
                                >
                                    <mat-icon>delete</mat-icon>
                                </button>
                            </td>
                        </ng-container>
                        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
                        <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
                        <tr class="mat-row" *matNoDataRow>
                            <td class="mat-cell no-data" [attr.colspan]="displayedColumns.length">
                                {{ "users.list.noMatch" | transloco }}
                            </td>
                        </tr>
                    </table>
                </div>
            }
        </div>
    `,
    styles: [
        `
            .app-note {
                margin: 12px 14px 0;
            }

            .users-table {
                width: 100%;
                min-width: min(640px, 80vw);
            }

            .no-data {
                padding: 16px;
                color: var(--app-text-3);
            }

            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class TenantUsersPanelComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);
    private destroyRef = inject(DestroyRef);
    readonly scope = input.required<TenantScope>();

    readonly unfiltered = this.store.selectSignal(TenantUserSelectors.selectTenantUsers);
    readonly query = signal("");
    readonly users = computed(() => filterTenantUsers(this.unfiltered(), this.query()));
    readonly loading = this.store.selectSignal(TenantUserSelectors.selectTenantUsersLoading);

    readonly wamEnabled = this.store.selectSignal(AuthSelectors.selectWamEnabled);
    /** The admin's view of a tenant - only the admin adds a user of another tenant. */
    readonly byAdmin = computed(() => this.scope() !== "self");
    readonly createdByAdminOnly = computed(() => usersCreatedByAdminOnly(this.scope(), this.wamEnabled()));

    readonly displayedColumns = ["user", "admin", "groups", "language", "active", "lastLoginAt", "actions"];
    readonly groups = this.store.selectSignal(GroupSelectors.selectGroups);
    readonly trackById = (_index: number, user: TenantUser) => user.id;

    status(user: TenantUser): {label: string; tone: string} {
        return tenantUserStatus(user, this.wamEnabled());
    }

    /** Its membership comes from the IDM - a group with a WAM group (created from the groups header, or listed). */
    isFromIdm(user: TenantUser): boolean {
        return fromIdm(user, this.groups());
    }

    groupsOf(user: TenantUser): GroupText {
        return groupNames(user.groupIds, this.groups(), user.admin);
    }

    languageOf(user: TenantUser): string {
        return languageLabel(user.language);
    }

    initialsOf(name: string): string {
        return initials(name);
    }

    constructor() {
        this.notifyOn(TenantUserActions.createUserSuccess, () => this.translate("users.snack.created"));
        this.notifyOn(TenantUserActions.updateUserSuccess, () => this.translate("users.snack.updated"));
        this.notifyOn(TenantUserActions.deleteUserSuccess, () => this.translate("users.snack.deleted"));
        this.notifyOn(
            TenantUserActions.createUserFailure,
            ({error}) => this.translate("users.snack.createError", {error}),
            true,
        );
        this.notifyOn(
            TenantUserActions.updateUserFailure,
            ({error}) => this.translate("users.snack.updateError", {error}),
            true,
        );
        this.notifyOn(
            TenantUserActions.deleteUserFailure,
            ({error}) => this.translate("users.snack.deleteError", {error}),
            true,
        );
    }

    ngOnInit(): void {
        this.store.dispatch(GroupActions.loadGroups({scope: this.scope()}));
        this.store.dispatch(TenantUserActions.loadUsers({scope: this.scope()}));
    }

    openCreateDialog(): void {
        this.openUserDialog(null, {
            action: user => TenantUserActions.createUser({scope: this.scope(), user}),
            success: [TenantUserActions.createUserSuccess],
            failure: TenantUserActions.createUserFailure,
        });
    }

    openFromTenantDialog(): void {
        const tenantId = this.scope();
        if (tenantId === "self") {
            return;
        }
        this.dialogSave.open<UserFromTenantDialogComponent, UserFromTenantDialogData, UserFromTenantRequest>(
            UserFromTenantDialogComponent,
            {width: "520px", data: {tenantId}},
            {
                action: request => TenantUserActions.addUserFromTenant({tenantId, request}),
                success: [TenantUserActions.createUserSuccess],
                failure: TenantUserActions.createUserFailure,
            },
        );
    }

    openEditDialog(existing: TenantUser): void {
        this.openUserDialog(existing, {
            action: user => TenantUserActions.updateUser({scope: this.scope(), userId: existing.id, user}),
            success: [TenantUserActions.updateUserSuccess],
            failure: TenantUserActions.updateUserFailure,
        });
    }

    confirmDelete(user: TenantUser): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.translate("users.confirmDelete", {username: user.username}),
                    confirmLabel: this.translate("common.delete"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(TenantUserActions.deleteUser({scope: this.scope(), userId: user.id})));
    }

    private openUserDialog(user: TenantUser | null, spec: DialogSaveSpec<TenantUserRequest>): void {
        this.dialogSave.open<TenantUserDialogComponent, TenantUserDialogData, TenantUserRequest>(
            TenantUserDialogComponent,
            {width: "520px", data: {user, usernameFixed: this.createdByAdminOnly(), groups: this.groups()}},
            spec,
        );
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
