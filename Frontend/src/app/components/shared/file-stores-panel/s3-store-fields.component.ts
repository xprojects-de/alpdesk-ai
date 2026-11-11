import {Component, input} from "@angular/core";
import {ReactiveFormsModule} from "@angular/forms";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatInputModule} from "@angular/material/input";
import {TranslocoPipe} from "@jsverse/transloco";
import {DEFAULT_S3_REGION} from "../../../models/file-store.model";
import {FileStoreForm, STORE_FIELDS_STYLES} from "./file-store-form";

/** The fields of an S3 store: endpoint (empty: Amazon S3), bucket, region and access key. */
@Component({
    selector: "app-s3-store-fields",
    imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, TranslocoPipe],
    template: `
        <div class="fields" [formGroup]="form()">
            <mat-form-field appearance="outline">
                <mat-label>{{ "fileStores.dialog.endpoint" | transloco }}</mat-label>
                <input
                    matInput
                    formControlName="endpoint"
                    [placeholder]="'fileStores.dialog.endpointPlaceholder' | transloco"
                />
                <mat-hint>{{ "fileStores.dialog.endpointHint" | transloco }}</mat-hint>
            </mat-form-field>
            <div class="row">
                <mat-form-field appearance="outline" class="wide">
                    <mat-label>{{ "fileStores.dialog.bucket" | transloco }}</mat-label>
                    <input matInput formControlName="bucket" />
                    @if (form().controls.bucket.hasError("required") && form().controls.bucket.touched) {
                        <mat-error>{{ "fileStores.dialog.bucketRequired" | transloco }}</mat-error>
                    }
                </mat-form-field>
                <mat-form-field appearance="outline" class="region">
                    <mat-label>{{ "fileStores.dialog.region" | transloco }}</mat-label>
                    <input matInput formControlName="region" [placeholder]="regionPlaceholder()" />
                    @if (form().controls.region.hasError("required") && form().controls.region.touched) {
                        <mat-error>{{ "fileStores.dialog.regionRequired" | transloco }}</mat-error>
                    }
                </mat-form-field>
            </div>
            <mat-form-field appearance="outline">
                <mat-label>{{ "fileStores.dialog.accessKeyId" | transloco }}</mat-label>
                <input matInput formControlName="accessKeyId" autocomplete="off" />
                <mat-hint>{{ "fileStores.dialog.accessKeyIdHint" | transloco }}</mat-hint>
                @if (form().controls.accessKeyId.hasError("required") && form().controls.accessKeyId.touched) {
                    <mat-error>{{ "fileStores.dialog.accessKeyIdRequired" | transloco }}</mat-error>
                }
            </mat-form-field>
        </div>
    `,
    styles: [STORE_FIELDS_STYLES],
})
export class S3StoreFieldsComponent {
    readonly form = input.required<FileStoreForm>();

    /** A compatible store takes the default region when none is entered; Amazon S3 needs one, e.g. eu-central-1. */
    regionPlaceholder(): string {
        return this.form().controls.endpoint.value.trim() ? DEFAULT_S3_REGION : "eu-central-1";
    }
}
