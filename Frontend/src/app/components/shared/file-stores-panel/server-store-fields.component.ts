import {Component, input} from "@angular/core";
import {ReactiveFormsModule} from "@angular/forms";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {TranslocoPipe} from "@jsverse/transloco";
import {DEFAULT_PORTS, ServerStoreType} from "../../../models/file-store.model";
import {FileStoreForm, STORE_FIELDS_STYLES} from "./file-store-form";

/** The fields of a store reached as a server (SFTP, FTPS): host, port and user. */
@Component({
    selector: "app-server-store-fields",
    imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, TranslocoPipe],
    template: `
        <div class="fields" [formGroup]="form()">
            <div class="row">
                <mat-form-field appearance="outline" class="wide">
                    <mat-label>{{ "fileStores.dialog.host" | transloco }}</mat-label>
                    <input
                        matInput
                        formControlName="host"
                        [placeholder]="'fileStores.dialog.hostPlaceholder' | transloco"
                    />
                    <mat-hint>{{ "fileStores.dialog.hostHint" | transloco }}</mat-hint>
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
        </div>
    `,
    styles: [STORE_FIELDS_STYLES],
})
export class ServerStoreFieldsComponent {
    readonly form = input.required<FileStoreForm>();

    /** The type's port, shown in the empty port field. */
    defaultPort(): number {
        return DEFAULT_PORTS[this.form().controls.type.value as ServerStoreType];
    }
}
