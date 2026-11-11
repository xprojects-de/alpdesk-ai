import {Component, inject} from "@angular/core";
import {FormBuilder, ReactiveFormsModule, Validators} from "@angular/forms";
import {MatDialogRef, MAT_DIALOG_DATA, MatDialogModule} from "@angular/material/dialog";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatButtonModule} from "@angular/material/button";
import {MatSlideToggleModule} from "@angular/material/slide-toggle";
import {MatSelectModule} from "@angular/material/select";
import {UserGroup} from "../../../models/user-group.model";
import {TenantUser, TenantUserRequest} from "../../../models/tenant-user.model";
import {buildTenantUserRequest, withUnlistedGroups} from "../../../utils/tenant-user.util";
import {DEFAULT_LANGUAGE, LANGUAGES, languageOf} from "../../../utils/language.util";
import {notBlank, PASSWORD_MIN_LENGTH, username} from "../../../utils/validators.util";
import {dialogSave} from "../dialog-save";
import {Store} from "@ngrx/store";
import {defaultGroupIds} from "../../../utils/user-group.util";
import {TranslocoPipe} from "@jsverse/transloco";
import * as AuthSelectors from "../../../store/auth/auth.selectors";

/** What the dialog is opened with: the user to edit (null to create), and whether its username may not be changed. */
export interface TenantUserDialogData {
    user: TenantUser | null;
    /** With the WAM proxy the username is the person's sign-in there - only the admin changes it. */
    usernameFixed: boolean;
    /** The tenant's user groups to choose from. */
    groups: UserGroup[];
}

/** Create/edit dialog of a tenant user: opened with the user to edit, or null to create. */
@Component({
    selector: "app-tenant-user-dialog",
    imports: [
        TranslocoPipe,
        ReactiveFormsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatButtonModule,
        MatSlideToggleModule,
        MatSelectModule,
    ],
    template: `
        <h2 mat-dialog-title>{{ (user ? "users.dialog.editTitle" : "users.dialog.createTitle") | transloco }}</h2>
        <mat-dialog-content>
            <form id="tenant-user-form" [formGroup]="form" class="user-form" (ngSubmit)="onSave()">
                <mat-form-field appearance="outline">
                    <mat-label>{{ "users.dialog.username" | transloco }}</mat-label>
                    <input matInput formControlName="username" required autocomplete="off" cdkFocusInitial />
                    @if (data.usernameFixed) {
                        <mat-hint>{{ "users.dialog.usernameFixedHint" | transloco }}</mat-hint>
                    } @else {
                        <mat-hint>{{ "users.dialog.usernameHint" | transloco }}</mat-hint>
                    }
                    @if (form.controls.username.hasError("required") && form.controls.username.touched) {
                        <mat-error>{{ "users.dialog.usernameRequired" | transloco }}</mat-error>
                    }
                    @if (form.controls.username.hasError("username")) {
                        <mat-error>{{ "users.dialog.usernameInvalid" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline">
                    <mat-label>{{ "common.name" | transloco }}</mat-label>
                    <input matInput formControlName="displayName" required />
                    @if (form.controls.displayName.hasError("required") && form.controls.displayName.touched) {
                        <mat-error>{{ "users.dialog.nameRequired" | transloco }}</mat-error>
                    }
                    @if (form.controls.displayName.hasError("blank")) {
                        <mat-error>{{ "users.dialog.nameBlank" | transloco }}</mat-error>
                    }
                </mat-form-field>
                @if (wamEnabled()) {
                    <p class="hint">{{ "users.dialog.wamHint" | transloco }}</p>
                } @else {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "users.dialog.password" | transloco }}</mat-label>
                        <input
                            matInput
                            type="password"
                            formControlName="password"
                            [required]="!user"
                            autocomplete="new-password"
                        />
                        <mat-hint>
                            {{
                                (user ? "users.dialog.passwordHintEdit" : "users.dialog.minLength")
                                    | transloco: {min: passwordMinLength}
                            }}
                        </mat-hint>
                        @if (form.controls.password.hasError("required") && form.controls.password.touched) {
                            <mat-error>{{ "users.dialog.passwordRequired" | transloco }}</mat-error>
                        }
                        @if (form.controls.password.hasError("minlength")) {
                            <mat-error>{{ "users.dialog.minLength" | transloco: {min: passwordMinLength} }}</mat-error>
                        }
                    </mat-form-field>
                }
                <mat-form-field appearance="outline">
                    <mat-label>{{ "users.dialog.language" | transloco }}</mat-label>
                    <mat-select formControlName="language">
                        @for (language of languages; track language.value) {
                            <mat-option [value]="language.value">{{ language.label }}</mat-option>
                        }
                    </mat-select>
                    <mat-hint>{{ "users.dialog.languageHint" | transloco }}</mat-hint>
                </mat-form-field>
                <mat-slide-toggle formControlName="active">{{ "users.dialog.active" | transloco }}</mat-slide-toggle>
                <mat-slide-toggle formControlName="admin">
                    {{ "users.dialog.admin" | transloco }}
                </mat-slide-toggle>
                @if (data.groups.length > 0) {
                    <mat-form-field appearance="outline">
                        <mat-label>{{ "users.dialog.groups" | transloco }}</mat-label>
                        <mat-select formControlName="groupIds" multiple>
                            @for (group of data.groups; track group.id) {
                                <mat-option [value]="group.id">{{ group.name }}</mat-option>
                            }
                        </mat-select>
                        <mat-hint>
                            {{
                                (form.controls.admin.value ? "users.dialog.groupsHintAdmin" : "users.dialog.groupsHint")
                                    | transloco
                            }}
                        </mat-hint>
                    </mat-form-field>
                } @else {
                    <p class="hint">{{ "users.dialog.noGroups" | transloco }}</p>
                }
                @if (wamGroupsChecked() && user) {
                    <div class="wam-groups">
                        <div class="label">{{ "users.dialog.wamGroups" | transloco }}</div>
                        @if (user.wamGroups.length > 0) {
                            <div class="tags">
                                @for (wamGroup of user.wamGroups; track wamGroup) {
                                    <span class="app-tag">{{ wamGroup }}</span>
                                }
                            </div>
                        } @else {
                            <div class="hint">{{ "users.dialog.wamGroupsNone" | transloco }}</div>
                        }
                    </div>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="onCancel()">{{ "common.cancel" | transloco }}</button>
            <!-- Submit button of the form above (via form=) so Enter in a field saves too. -->
            <button mat-flat-button type="submit" form="tenant-user-form" [disabled]="form.invalid || saver.saving()">
                {{ "common.save" | transloco }}
            </button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .user-form {
                display: flex;
                flex-direction: column;
                gap: 8px;
                min-width: min(420px, 70vw);
                margin-top: 16px;
            }

            mat-form-field {
                width: 100%;
            }

            mat-slide-toggle {
                margin: 4px 0 8px;
            }

            .hint {
                color: var(--app-text-2);
                margin: 0 0 8px;
            }

            .wam-groups {
                margin-top: 8px;
            }

            .wam-groups .label {
                font-weight: 500;
                margin-bottom: 4px;
            }

            .tags {
                display: flex;
                flex-wrap: wrap;
                gap: 6px;
            }
        `,
    ],
})
export class TenantUserDialogComponent {
    private fb = inject(FormBuilder);
    private dialogRef = inject<MatDialogRef<TenantUserDialogComponent, TenantUserRequest>>(MatDialogRef);
    /** Saves and closes only when saved - on an error (e.g. a username already taken) the dialog stays open. */
    readonly saver = dialogSave<TenantUserRequest>();
    readonly data = inject<TenantUserDialogData>(MAT_DIALOG_DATA);
    readonly user = this.data.user;
    readonly passwordMinLength = PASSWORD_MIN_LENGTH;
    readonly languages = LANGUAGES;
    private readonly store = inject(Store);
    readonly wamEnabled = this.store.selectSignal(AuthSelectors.selectWamEnabled);
    readonly wamGroupsChecked = this.store.selectSignal(AuthSelectors.selectWamGroupsChecked);

    form = this.fb.nonNullable.group({
        username: [
            {value: this.user?.username ?? "", disabled: this.data.usernameFixed},
            [Validators.required, username()],
        ],
        displayName: [this.user?.displayName ?? "", [Validators.required, notBlank()]],
        // Required only on create: on edit an empty password keeps the current one. With WAM there is none.
        password: [
            "",
            this.user || this.wamEnabled()
                ? [Validators.minLength(PASSWORD_MIN_LENGTH)]
                : [Validators.required, Validators.minLength(PASSWORD_MIN_LENGTH)],
        ],
        active: [this.user?.active ?? true],
        admin: [this.user?.admin ?? false],
        groupIds: [this.user?.groupIds ?? defaultGroupIds(this.data.groups)],
        // The language the frontend speaks for the user: a tag it does not offer ("de-AT", one removed since, none) is
        // preset as that - saving then stores an offered one.
        language: [this.user ? languageOf(this.user.language) : DEFAULT_LANGUAGE],
    });

    onCancel(): void {
        this.dialogRef.close();
    }

    onSave(): void {
        if (this.form.invalid) {
            return;
        }
        const value = this.form.getRawValue();
        const original = this.user?.groupIds ?? null;
        const listed = this.data.groups.map(group => group.id);
        const groupIds = original === null ? value.groupIds : withUnlistedGroups(value.groupIds, original, listed);
        this.saver.submit(buildTenantUserRequest({...value, groupIds}, original));
    }
}
