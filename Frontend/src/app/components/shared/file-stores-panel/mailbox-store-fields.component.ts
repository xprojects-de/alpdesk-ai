import {Component, input} from "@angular/core";
import {ReactiveFormsModule} from "@angular/forms";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {MatSelectModule} from "@angular/material/select";
import {TranslocoPipe} from "@jsverse/transloco";
import {MAILBOX_PORTS, MAILBOX_SECURITIES} from "../../../models/file-store.model";
import {FileStoreForm, STORE_FIELDS_STYLES} from "./file-store-form";

/** The fields of a mailbox (IMAP): host, port, user, how it is reached, its folder and the days listed. */
@Component({
    selector: "app-mailbox-store-fields",
    imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, TranslocoPipe],
    template: `
        <div class="fields" [formGroup]="form()">
            <div class="row">
                <mat-form-field appearance="outline" class="wide">
                    <mat-label>{{ "fileStores.dialog.host" | transloco }}</mat-label>
                    <input
                        matInput
                        formControlName="host"
                        [placeholder]="'fileStores.dialog.mailHostPlaceholder' | transloco"
                    />
                    @if (form().controls.host.hasError("required") && form().controls.host.touched) {
                        <mat-error>{{ "fileStores.dialog.hostRequired" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline" class="port">
                    <mat-label>{{ "fileStores.dialog.port" | transloco }}</mat-label>
                    <input
                        matInput
                        type="number"
                        formControlName="port"
                        min="1"
                        max="65535"
                        [placeholder]="'' + defaultPort()"
                    />
                    @if (form().controls.port.invalid) {
                        <mat-error>{{ "fileStores.dialog.portInvalid" | transloco }}</mat-error>
                    }
                </mat-form-field>
            </div>
            <mat-form-field appearance="outline">
                <mat-label>{{ "fileStores.dialog.username" | transloco }}</mat-label>
                <input matInput formControlName="username" autocomplete="off" />
                @if (form().controls.username.hasError("required") && form().controls.username.touched) {
                    <mat-error>{{ "fileStores.dialog.usernameRequired" | transloco }}</mat-error>
                }
            </mat-form-field>
            <mat-form-field appearance="outline">
                <mat-label>{{ "fileStores.dialog.security" | transloco }}</mat-label>
                <mat-select formControlName="security">
                    @for (security of securities; track security) {
                        <mat-option [value]="security">{{
                            "fileStores.dialog.securityOption." + security | transloco
                        }}</mat-option>
                    }
                </mat-select>
            </mat-form-field>
            <div class="row">
                <mat-form-field appearance="outline" class="wide">
                    <mat-label>{{ "fileStores.dialog.mailbox" | transloco }}</mat-label>
                    <input matInput formControlName="mailbox" [placeholder]="'INBOX'" />
                    <mat-hint>{{ "fileStores.dialog.mailboxHint" | transloco }}</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline" class="region">
                    <mat-label>{{ "fileStores.dialog.days" | transloco }}</mat-label>
                    <input matInput type="number" formControlName="days" min="1" max="3650" placeholder="365" />
                    <mat-hint>{{ "fileStores.dialog.daysHint" | transloco }}</mat-hint>
                    @if (form().controls.days.invalid) {
                        <mat-error>{{ "fileStores.dialog.daysInvalid" | transloco }}</mat-error>
                    }
                </mat-form-field>
            </div>
        </div>
    `,
    styles: [STORE_FIELDS_STYLES],
})
export class MailboxStoreFieldsComponent {
    readonly form = input.required<FileStoreForm>();
    readonly securities = MAILBOX_SECURITIES;

    /** The port of the chosen way, shown in the empty port field. */
    defaultPort(): number {
        return MAILBOX_PORTS[this.form().controls.security.value];
    }
}
