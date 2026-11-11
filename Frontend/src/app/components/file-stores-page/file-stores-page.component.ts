import {Component} from "@angular/core";
import {TranslocoPipe} from "@jsverse/transloco";
import {FileStoresPanelComponent} from "../shared/file-stores-panel/file-stores-panel.component";
import {PageHeaderComponent} from "../shared/page-header/page-header.component";

/** Tenant admin's page "Dateiablagen": the SFTP and FTPS servers the app reads files from and writes files to. */
@Component({
    selector: "app-file-stores-page",
    imports: [FileStoresPanelComponent, PageHeaderComponent, TranslocoPipe],
    template: `
        <div class="page">
            <app-page-header
                [title]="'fileStores.page.title' | transloco"
                [subtitle]="'fileStores.page.subtitle' | transloco"
            />
            <app-file-stores-panel scope="self" />
        </div>
    `,
})
export class FileStoresPageComponent {}
