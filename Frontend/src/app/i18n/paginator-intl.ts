import {inject, Injectable} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {MatPaginatorIntl} from "@angular/material/paginator";
import {TranslocoService} from "@jsverse/transloco";

/**
 * The paginator's labels in the active language. Without the "Items per page:" label - the page size select is
 * self-explanatory, the label only takes up space next to it.
 */
@Injectable()
export class TranslatedPaginatorIntl extends MatPaginatorIntl {
    private transloco = inject(TranslocoService);

    constructor() {
        super();
        this.itemsPerPageLabel = "";
        // Follows every language switch, once its file is loaded.
        this.transloco
            .selectTranslation()
            .pipe(takeUntilDestroyed())
            .subscribe(() => this.applyLabels());
    }

    override getRangeLabel = (page: number, pageSize: number, length: number): string => {
        if (length === 0 || pageSize === 0) {
            return this.transloco.translate("paginator.rangeEmpty", {length});
        }
        const start = page * pageSize + 1;
        const end = Math.min(start + pageSize - 1, length);
        return this.transloco.translate("paginator.range", {start, end, length});
    };

    private applyLabels(): void {
        this.nextPageLabel = this.transloco.translate("paginator.next");
        this.previousPageLabel = this.transloco.translate("paginator.previous");
        this.firstPageLabel = this.transloco.translate("paginator.first");
        this.lastPageLabel = this.transloco.translate("paginator.last");
        this.changes.next();
    }
}
