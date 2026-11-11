import {Component, computed, inject, signal} from "@angular/core";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MatDialogRef, MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatButtonModule} from "@angular/material/button";
import {MatSelectModule} from "@angular/material/select";
import {MatIconModule} from "@angular/material/icon";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {TranslocoPipe} from "@jsverse/transloco";
import {KnowledgeArea} from "../../../models/knowledge-area.model";
import {TenantUser} from "../../../models/tenant-user.model";
import {MCP_ACCESS_LABELS} from "../../../models/mcp.model";
import {UserGroup, UserGroupRequest} from "../../../models/user-group.model";
import {
    buildUserGroupRequest,
    GROUP_NAME_MAX,
    LevelChoice,
    levelChoices,
    memberNames,
    replacesMembers,
    WAM_GROUP_MAX,
} from "../../../utils/user-group.util";
import {toSignal} from "@angular/core/rxjs-interop";
import {filterTenantUsers} from "../../../utils/tenant-user.util";
import {notBlank} from "../../../utils/validators.util";
import {dialogSave} from "../dialog-save";

/** What the dialog is opened with: the group to edit (null to create), the tenant's users and areas to choose from. */
export interface UserGroupDialogData {
    group: UserGroup | null;
    users: TenantUser[];
    areas: KnowledgeArea[];
    /** The WAM proxy's IDM groups are checked: the group may name one. */
    wamGroupsChecked: boolean;
    /** With WAM on, a tenant admin sees the WAM group but cannot change it - it gives access, the admin's decision. */
    wamGroupByAdminOnly: boolean;
    /** The tenant has the web search: the group's switch is shown. */
    webSearch: boolean;
    /** The tenant's connected software - the group's level for each is set here too. */
    connections: {id: number; name: string}[];
}

/**
 * Create/edit dialog of a user group: its name, its members, its knowledge areas, its web search, its level for each
 * connected software and, with WAM, its IDM group - saved together, in one request.
 */
@Component({
    selector: "app-user-group-dialog",
    imports: [
        TranslocoPipe,
        ReactiveFormsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatButtonModule,
        MatSelectModule,
        MatIconModule,
        MatSlideToggleModule,
    ],
    template: `
        <h2 mat-dialog-title>{{ (group ? "groups.dialog.editTitle" : "groups.dialog.createTitle") | transloco }}</h2>
        <mat-dialog-content>
            <form id="user-group-form" [formGroup]="form" class="group-form" (ngSubmit)="onSave()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "common.name" | transloco }}</mat-label>
                    <input matInput formControlName="name" required [maxlength]="nameMax" cdkFocusInitial />
                    @if (form.controls.name.hasError("required") && form.controls.name.touched) {
                        <mat-error>{{ "groups.dialog.nameRequired" | transloco }}</mat-error>
                    }
                    @if (form.controls.name.hasError("blank")) {
                        <mat-error>{{ "groups.dialog.nameRequired" | transloco }}</mat-error>
                    }
                </mat-form-field>
                @if (wamGroupSet()) {
                    <div class="idm-members">
                        <div class="section">{{ "groups.dialog.membersFromIdm" | transloco }}</div>
                        <p class="hint">{{ idmMembers().key | transloco: idmMembers().params }}</p>
                        <p class="hint">{{ "groups.dialog.membersFromIdmHint" | transloco }}</p>
                        @if (replacesAssigned()) {
                            <p class="app-note warn">{{ "groups.dialog.membersReplaced" | transloco }}</p>
                        }
                    </div>
                }
                <mat-form-field appearance="outline" [class.hidden-field]="wamGroupSet()">
                    <mat-label>{{ "groups.dialog.members" | transloco }}</mat-label>
                    <mat-select
                        formControlName="userIds"
                        multiple
                        (openedChange)="onMembersOpened($event, memberSearch)"
                    >
                        <!-- Keys typed here stay in the field: mat-select would take them to pick an option. -->
                        <div class="member-search">
                            <mat-icon>search</mat-icon>
                            <input
                                #memberSearch
                                [placeholder]="'groups.dialog.memberSearch' | transloco"
                                [attr.aria-label]="'groups.dialog.memberSearch' | transloco"
                                [value]="memberQuery()"
                                (input)="memberQuery.set(memberSearch.value)"
                                (keydown)="$event.stopPropagation()"
                            />
                        </div>
                        <!-- Hidden, not left out: an option left out drops its user from the selection on the next click.
                             Disabled too, so arrow keys and typing never reach one that cannot be seen. -->
                        @for (user of data.users; track user.id) {
                            <mat-option
                                [value]="user.id"
                                [class.hidden-option]="!shownUserIds().has(user.id)"
                                [disabled]="!shownUserIds().has(user.id)"
                            >
                                {{ user.displayName }} ({{ user.username }})
                            </mat-option>
                        }
                        @if (data.users.length > 0 && shownUserIds().size === 0) {
                            <div class="no-match">{{ "groups.dialog.noMemberMatch" | transloco }}</div>
                        }
                    </mat-select>
                </mat-form-field>
                @if (data.areas.length > 0) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "groups.dialog.areas" | transloco }}</mat-label>
                        <mat-select formControlName="areaIds" multiple>
                            @for (area of data.areas; track area.id) {
                                <mat-option [value]="area.id">{{ area.name }}</mat-option>
                            }
                        </mat-select>
                        <mat-hint>{{ "groups.dialog.areasHint" | transloco }}</mat-hint>
                    </mat-form-field>
                }
                @if (data.wamGroupsChecked || group?.wamGroup) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "groups.dialog.wamGroup" | transloco }}</mat-label>
                        <input matInput formControlName="wamGroup" [maxlength]="wamGroupMax" autocomplete="off" />
                        <mat-hint>{{
                            (data.wamGroupByAdminOnly ? "groups.dialog.wamGroupByAdmin" : "groups.dialog.wamGroupHint")
                                | transloco
                        }}</mat-hint>
                    </mat-form-field>
                }
                @if (data.webSearch) {
                    <mat-slide-toggle formControlName="webSearch">{{
                        "groups.dialog.webSearch" | transloco
                    }}</mat-slide-toggle>
                    <p class="hint">{{ "groups.dialog.webSearchHint" | transloco }}</p>
                }
                @if (data.connections.length > 0) {
                    <div class="section">{{ "groups.dialog.software" | transloco }}</div>
                    <p class="hint">{{ "groups.dialog.softwareLevelsHint" | transloco }}</p>
                    <div formGroupName="levels" class="levels">
                        @for (connection of data.connections; track connection.id) {
                            <div class="level-row">
                                <span class="level-name">{{ connection.name }}</span>
                                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                                    <mat-select
                                        [formControlName]="connection.id.toString()"
                                        [attr.aria-label]="
                                            'groups.dialog.levelFor' | transloco: {name: connection.name}
                                        "
                                    >
                                        <mat-option value="NONE">{{
                                            "mcpConnections.access.none" | transloco
                                        }}</mat-option>
                                        <mat-option value="READ">{{ levelLabels.READ | transloco }}</mat-option>
                                        <mat-option value="WRITE">{{ levelLabels.WRITE | transloco }}</mat-option>
                                    </mat-select>
                                </mat-form-field>
                            </div>
                        }
                    </div>
                } @else {
                    <p class="hint">{{ "groups.dialog.softwareHint" | transloco }}</p>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <!-- Submit button of the form above (via form=) so Enter in a field saves too. -->
            <button mat-flat-button type="submit" form="user-group-form" [disabled]="form.invalid || saver.saving()">
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .group-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(420px, 70vw);
                margin-top: 16px;
            }

            mat-form-field {
                width: 100%;
            }

            .hint {
                color: var(--app-text-2);
                margin: 8px 0 0;
            }

            .section {
                font-weight: 500;
                margin-top: 12px;
            }

            .levels {
                display: flex;
                flex-direction: column;
                gap: 6px;
                margin-top: 4px;
            }

            .level-row {
                display: flex;
                align-items: center;
                gap: 12px;
            }

            .level-name {
                flex: 1 1 auto;
                min-width: 0;
                overflow-wrap: anywhere;
            }

            .level-row mat-form-field {
                flex: 0 0 200px;
                width: 200px;
            }

            .member-search {
                position: sticky;
                top: 0;
                z-index: 1;
                display: flex;
                align-items: center;
                gap: 8px;
                padding: 8px 16px;
                background: var(--mat-sys-surface-container);
                border-bottom: 1px solid var(--app-border);
            }

            .member-search input {
                flex: 1;
                border: none;
                outline: none;
                background: transparent;
                color: inherit;
                font: inherit;
            }

            .hidden-option {
                display: none;
            }

            .hidden-field {
                display: none;
            }

            .idm-members {
                margin-bottom: 8px;
            }

            .no-match {
                padding: 12px 16px;
                color: var(--app-text-3);
            }
        `,
    ],
})
export class UserGroupDialogComponent {
    private fb = inject(FormBuilder);
    private dialogRef = inject<MatDialogRef<UserGroupDialogComponent, UserGroupRequest>>(MatDialogRef);
    /** Saves and closes only when saved - on an error (e.g. a name already taken) the dialog stays open. */
    readonly saver = dialogSave<UserGroupRequest>();
    readonly data = inject<UserGroupDialogData>(MAT_DIALOG_DATA);
    readonly group = this.data.group;
    readonly nameMax = GROUP_NAME_MAX;
    readonly wamGroupMax = WAM_GROUP_MAX;
    readonly levelLabels = MCP_ACCESS_LABELS;

    /** What the members' dropdown is searched for - by name or username. */
    readonly memberQuery = signal("");
    readonly shownUserIds = computed(
        () => new Set(filterTenantUsers(this.data.users, this.memberQuery()).map(user => user.id)),
    );

    form = this.fb.nonNullable.group({
        name: [this.group?.name ?? "", [Validators.required, notBlank(), Validators.maxLength(GROUP_NAME_MAX)]],
        wamGroup: [this.group?.wamGroup ?? "", [Validators.maxLength(WAM_GROUP_MAX)]],
        userIds: [this.group?.userIds ?? []],
        areaIds: [this.group?.areaIds ?? []],
        webSearch: [this.group?.webSearch ?? false],
        levels: this.fb.nonNullable.record<LevelChoice>(levelChoices(this.group?.levels ?? [], this.data.connections)),
    });

    constructor() {
        if (this.data.wamGroupByAdminOnly) {
            this.form.controls.wamGroup.disable();
        }
    }

    /** With a WAM group the IDM gives the members - the picker is hidden, the header's members shown. */
    private readonly wamGroupValue = toSignal(this.form.controls.wamGroup.valueChanges, {
        initialValue: this.form.controls.wamGroup.value,
    });
    readonly wamGroupSet = computed(() => this.wamGroupValue().trim().length > 0);
    readonly replacesAssigned = computed(() => replacesMembers(this.group, this.wamGroupValue()));
    readonly idmMembers = computed(() => memberNames(this.group?.wamGroup ? this.group.userIds : [], this.data.users));

    /** Opened: the cursor goes into the search; closed: the search is cleared for the next time. */
    onMembersOpened(opened: boolean, search: HTMLInputElement): void {
        if (opened) {
            search.focus();
        } else {
            this.memberQuery.set("");
        }
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        this.saver.submit(buildUserGroupRequest(this.form.getRawValue()));
    }
}
