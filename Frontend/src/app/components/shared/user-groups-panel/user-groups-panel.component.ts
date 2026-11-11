import {Component, DestroyRef, inject, input, OnInit} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
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
import {UserGroup, UserGroupRequest} from "../../../models/user-group.model";
import * as GroupActions from "../../../store/user-group/user-group.actions";
import * as GroupSelectors from "../../../store/user-group/user-group.selectors";
import * as TenantUserSelectors from "../../../store/tenant-user/tenant-user.selectors";
import * as AreaActions from "../../../store/knowledge-area/knowledge-area.actions";
import * as AreaSelectors from "../../../store/knowledge-area/knowledge-area.selectors";
import * as McpConnectionActions from "../../../store/mcp-connection/mcp-connection.actions";
import * as McpConnectionSelectors from "../../../store/mcp-connection/mcp-connection.selectors";
import * as AuthSelectors from "../../../store/auth/auth.selectors";
import {areaNames, AreaText} from "../../../utils/knowledge-area.util";
import {groupSoftware, GroupText, memberNames} from "../../../utils/user-group.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {DialogSave, DialogSaveSpec} from "../dialog-save";
import {EmptyStateComponent} from "../empty-state/empty-state.component";
import {UserGroupDialogComponent, UserGroupDialogData} from "./user-group-dialog.component";
import {tenantWebSearch} from "../tenant-web-search";

/**
 * One tenant's user groups - its members' knowledge areas here, their levels per software on the software's access
 * list: the tenant admin's page "Benutzer" (scope "self") or the admin's tab of the tenant (a tenant's id). Placed below
 * the users panel, which loads the users and the groups.
 */
@Component({
    selector: "app-user-groups-panel",
    imports: [
        TranslocoPipe,
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
                    <div class="app-panel-title">{{ "groups.title" | transloco }}</div>
                    <div class="muted">{{ "groups.subtitle" | transloco }}</div>
                </div>
                <span class="spacer"></span>
                <button mat-stroked-button (click)="openCreateDialog()">
                    <mat-icon>group_add</mat-icon>
                    {{ "groups.create" | transloco }}
                </button>
            </div>

            @if (loading()) {
                <mat-progress-bar mode="indeterminate" />
            }

            @if (groups().length === 0 && !loading()) {
                <app-empty-state
                    icon="groups"
                    [title]="'groups.empty.title' | transloco"
                    [text]="'groups.empty.text' | transloco"
                >
                    <button mat-flat-button (click)="openCreateDialog()">
                        <mat-icon>group_add</mat-icon>
                        {{ "groups.create" | transloco }}
                    </button>
                </app-empty-state>
            } @else {
                <div class="table-container">
                    <table mat-table [dataSource]="groups()" [trackBy]="trackById" class="groups-table">
                        <ng-container matColumnDef="name">
                            <th mat-header-cell *matHeaderCellDef>{{ "groups.column.name" | transloco }}</th>
                            <td mat-cell *matCellDef="let group">
                                <div class="cell-title">
                                    {{ group.name }}
                                    @if (group.webSearch && webSearch()) {
                                        <span class="app-tag">{{ "groups.webSearchTag" | transloco }}</span>
                                    }
                                </div>
                                @if (group.wamGroup) {
                                    <!-- Unchecked, a group with a WAM group counts for nobody (fail closed) - said here. -->
                                    <div class="cell-sub" [class.unchecked]="!wamGroupsChecked()">
                                        {{
                                            (wamGroupsChecked() ? "groups.wamGroupOf" : "groups.wamGroupUnchecked")
                                                | transloco: {wamGroup: group.wamGroup}
                                        }}
                                    </div>
                                }
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="members">
                            <th mat-header-cell *matHeaderCellDef>{{ "groups.column.members" | transloco }}</th>
                            <td mat-cell *matCellDef="let group" class="muted">
                                @let members = membersOf(group);
                                {{ members.key | transloco: members.params }}
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="areas">
                            <th mat-header-cell *matHeaderCellDef>{{ "groups.column.areas" | transloco }}</th>
                            <td mat-cell *matCellDef="let group" class="muted">
                                @let areaText = areasOf(group);
                                {{ areaText.key | transloco: areaText.params }}
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="software">
                            <th mat-header-cell *matHeaderCellDef>{{ "groups.column.software" | transloco }}</th>
                            <td mat-cell *matCellDef="let group">
                                <div class="software">
                                    @for (software of softwareOf(group); track software.name) {
                                        <span class="app-tag"
                                            >{{ software.name }} · {{ software.levelKey | transloco }}</span
                                        >
                                    } @empty {
                                        <span class="muted">{{ "groups.noSoftware" | transloco }}</span>
                                    }
                                </div>
                            </td>
                        </ng-container>
                        <ng-container matColumnDef="actions">
                            <th mat-header-cell *matHeaderCellDef class="actions-column"></th>
                            <td mat-cell *matCellDef="let group" class="actions-column">
                                <button
                                    mat-icon-button
                                    (click)="openEditDialog(group)"
                                    [matTooltip]="'common.edit' | transloco"
                                >
                                    <mat-icon>edit</mat-icon>
                                </button>
                                <button
                                    mat-icon-button
                                    class="danger"
                                    (click)="confirmDelete(group)"
                                    [matTooltip]="'common.delete' | transloco"
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
            .groups-table {
                width: 100%;
                min-width: min(640px, 80vw);
            }

            .danger mat-icon {
                color: var(--app-err);
            }

            .unchecked {
                color: var(--app-warn);
            }

            .software {
                display: flex;
                flex-wrap: wrap;
                gap: 4px;
            }
        `,
    ],
})
export class UserGroupsPanelComponent implements OnInit {
    private store = inject(Store);
    private actions$ = inject(Actions);
    private dialog = inject(MatDialog);
    private dialogSave = inject(DialogSave);
    private snackBar = inject(MatSnackBar);
    private transloco = inject(TranslocoService);
    private destroyRef = inject(DestroyRef);
    readonly scope = input.required<TenantScope>();
    /** Only a tenant with the web search sees its switch (and its tag). */
    readonly webSearch = tenantWebSearch(this.scope);

    readonly groups = this.store.selectSignal(GroupSelectors.selectGroups);
    readonly loading = this.store.selectSignal(GroupSelectors.selectGroupsLoading);
    readonly users = this.store.selectSignal(TenantUserSelectors.selectTenantUsers);
    readonly areas = this.store.selectSignal(AreaSelectors.selectAreas);
    readonly wamGroupsChecked = this.store.selectSignal(AuthSelectors.selectWamGroupsChecked);

    /** The tenant's connected software - a group's level for each is set in its dialog. */
    readonly connections = this.store.selectSignal(McpConnectionSelectors.selectConnections);
    readonly displayedColumns = ["name", "members", "areas", "software", "actions"];
    readonly trackById = (_index: number, group: UserGroup) => group.id;

    membersOf(group: UserGroup): GroupText {
        return memberNames(group.userIds, this.users());
    }

    softwareOf(group: UserGroup): {name: string; levelKey: string}[] {
        return groupSoftware(group.levels, this.connections());
    }

    areasOf(group: UserGroup): AreaText {
        return group.areaIds.length === 0
            ? {key: "groups.noAreas", params: {}}
            : areaNames(group.areaIds, this.areas());
    }

    constructor() {
        this.notifyOn(GroupActions.createGroupSuccess, () => this.translate("groups.snack.created"));
        this.notifyOn(GroupActions.updateGroupSuccess, () => this.translate("groups.snack.updated"));
        this.notifyOn(GroupActions.deleteGroupSuccess, () => this.translate("groups.snack.deleted"));
        this.notifyOn(
            GroupActions.createGroupFailure,
            ({error}) => this.translate("groups.snack.createError", {error}),
            true,
        );
        this.notifyOn(
            GroupActions.updateGroupFailure,
            ({error}) => this.translate("groups.snack.updateError", {error}),
            true,
        );
        this.notifyOn(
            GroupActions.deleteGroupFailure,
            ({error}) => this.translate("groups.snack.deleteError", {error}),
            true,
        );
    }

    ngOnInit(): void {
        this.store.dispatch(AreaActions.loadAreas({scope: this.scope()}));
        this.store.dispatch(McpConnectionActions.loadConnections({scope: this.scope()}));
    }

    openCreateDialog(): void {
        this.openGroupDialog(null, {
            action: group => GroupActions.createGroup({scope: this.scope(), group}),
            success: [GroupActions.createGroupSuccess],
            failure: GroupActions.createGroupFailure,
        });
    }

    openEditDialog(existing: UserGroup): void {
        this.openGroupDialog(existing, {
            action: group => GroupActions.updateGroup({scope: this.scope(), id: existing.id, group}),
            success: [GroupActions.updateGroupSuccess],
            failure: GroupActions.updateGroupFailure,
        });
    }

    confirmDelete(group: UserGroup): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.translate("groups.confirmDelete", {name: group.name}),
                    confirmLabel: this.translate("common.delete"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.store.dispatch(GroupActions.deleteGroup({scope: this.scope(), id: group.id})));
    }

    private openGroupDialog(group: UserGroup | null, spec: DialogSaveSpec<UserGroupRequest>): void {
        this.dialogSave.open<UserGroupDialogComponent, UserGroupDialogData, UserGroupRequest>(
            UserGroupDialogComponent,
            {
                width: "560px",
                data: {
                    group,
                    users: this.users(),
                    areas: this.areas(),
                    wamGroupsChecked: this.wamGroupsChecked(),
                    wamGroupByAdminOnly: this.scope() === "self",
                    webSearch: this.webSearch(),
                    connections: this.connections().map(connection => ({id: connection.id, name: connection.name})),
                },
            },
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
