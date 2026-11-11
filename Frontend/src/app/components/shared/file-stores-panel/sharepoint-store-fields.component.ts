import {Component, input} from "@angular/core";
import {ReactiveFormsModule} from "@angular/forms";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {TranslocoPipe} from "@jsverse/transloco";
import {FileStoreForm, STORE_FIELDS_STYLES} from "./file-store-form";

/**
 * The fields of a SharePoint store: the customer's Entra ID and app registration, the site's address (a OneDrive's
 * too) and its library (empty: the site's default one).
 */
@Component({
    selector: "app-sharepoint-store-fields",
    imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, TranslocoPipe],
    template: `
        <div class="fields" [formGroup]="form()">
            <mat-form-field appearance="outline">
                <mat-label>{{ "fileStores.dialog.siteUrl" | transloco }}</mat-label>
                <input
                    matInput
                    formControlName="siteUrl"
                    [placeholder]="'fileStores.dialog.siteUrlPlaceholder' | transloco"
                />
                <mat-hint>{{ "fileStores.dialog.siteUrlHint" | transloco }}</mat-hint>
                @if (form().controls.siteUrl.hasError("required") && form().controls.siteUrl.touched) {
                    <mat-error>{{ "fileStores.dialog.siteUrlRequired" | transloco }}</mat-error>
                }
            </mat-form-field>
            <mat-form-field appearance="outline">
                <mat-label>{{ "fileStores.dialog.library" | transloco }}</mat-label>
                <input matInput formControlName="library" />
                <mat-hint>{{ "fileStores.dialog.libraryHint" | transloco }}</mat-hint>
            </mat-form-field>
            <mat-form-field appearance="outline">
                <mat-label>{{ "fileStores.dialog.directoryId" | transloco }}</mat-label>
                <input matInput formControlName="directoryId" autocomplete="off" />
                <mat-hint>{{ "fileStores.dialog.directoryIdHint" | transloco }}</mat-hint>
                @if (form().controls.directoryId.hasError("required") && form().controls.directoryId.touched) {
                    <mat-error>{{ "fileStores.dialog.directoryIdRequired" | transloco }}</mat-error>
                }
            </mat-form-field>
            <mat-form-field appearance="outline">
                <mat-label>{{ "fileStores.dialog.clientId" | transloco }}</mat-label>
                <input matInput formControlName="clientId" autocomplete="off" />
                <mat-hint>{{ "fileStores.dialog.clientIdHint" | transloco }}</mat-hint>
                @if (form().controls.clientId.hasError("required") && form().controls.clientId.touched) {
                    <mat-error>{{ "fileStores.dialog.clientIdRequired" | transloco }}</mat-error>
                }
            </mat-form-field>
        </div>
    `,
    styles: [STORE_FIELDS_STYLES],
})
export class SharePointStoreFieldsComponent {
    readonly form = input.required<FileStoreForm>();
}
