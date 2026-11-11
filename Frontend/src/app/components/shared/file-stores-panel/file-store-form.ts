import {FormControl, FormGroup, NonNullableFormBuilder, Validators} from "@angular/forms";
import {FileStore, FILE_STORE_TYPES} from "../../../models/file-store.model";
import {FileStoreFormValue} from "../../../utils/file-store.util";
import {notBlank} from "../../../utils/validators.util";

/** The store dialog's form: one control per field of every type - the dialog shows the fields of the chosen one. */
export type FileStoreForm = FormGroup<{[K in keyof FileStoreFormValue]: FormControl<FileStoreFormValue[K]>}>;

/** The form for a new store (null) or one to edit. Which fields are required is set by the type (requiredFields). */
export function createFileStoreForm(fb: NonNullableFormBuilder, store: FileStore | null): FileStoreForm {
    return fb.group({
        name: [store?.name ?? "", [Validators.required, notBlank(), Validators.maxLength(80)]],
        type: [store?.type ?? FILE_STORE_TYPES[0]],
        host: [store?.host ?? "", [notBlank()]],
        port: new FormControl<number | null>(store?.port ?? null, [Validators.min(1), Validators.max(65535)]),
        username: [store?.username ?? "", [notBlank()]],
        password: [""],
        basePath: [store?.basePath ?? ""],
        endpoint: [store?.endpoint ?? ""],
        region: [store?.region ?? ""],
        bucket: [store?.bucket ?? "", [notBlank()]],
        accessKeyId: [store?.accessKeyId ?? "", [notBlank()]],
        directoryId: [store?.directoryId ?? "", [notBlank()]],
        clientId: [store?.clientId ?? "", [notBlank()]],
        siteUrl: [store?.siteUrl ?? "", [notBlank()]],
        library: [store?.library ?? ""],
        taskWrite: [store?.taskWrite ?? false],
        security: [store?.security ?? "SSL"],
        mailbox: [store?.mailbox ?? ""],
        days: new FormControl<number | null>(store?.days ?? null, [Validators.min(1), Validators.max(3650)]),
    }) as FileStoreForm;
}

/** The layout the field components of each type share. */
export const STORE_FIELDS_STYLES = `
    .fields {
        display: flex;
        flex-direction: column;
        gap: 8px;
    }

    mat-form-field {
        width: 100%;
    }

    .row {
        display: flex;
        gap: 12px;
    }

    .row .wide {
        flex: 1;
    }

    .row .port {
        width: 120px;
    }

    .row .region {
        width: 180px;
    }
`;
