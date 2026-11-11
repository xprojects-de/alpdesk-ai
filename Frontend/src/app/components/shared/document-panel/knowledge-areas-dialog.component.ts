import {Component, ElementRef, inject, signal, viewChild} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {MAT_DIALOG_DATA, MatDialog, MatDialogModule} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatIconModule} from "@angular/material/icon";
import {MatInputModule} from "@angular/material/input";
import {MatTooltipModule} from "@angular/material/tooltip";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {Actions, ofType} from "@ngrx/effects";
import {Store} from "@ngrx/store";
import {filter} from "rxjs/operators";
import {KnowledgeArea} from "../../../models/knowledge-area.model";
import {TenantScope} from "../../../models/tenant.model";
import * as AreaActions from "../../../store/knowledge-area/knowledge-area.actions";
import * as AreaSelectors from "../../../store/knowledge-area/knowledge-area.selectors";
import {areaName} from "../../../utils/knowledge-area.util";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";

export interface KnowledgeAreasDialogData {
    scope: TenantScope;
}

/**
 * The tenant's knowledge areas: add, rename, delete. Who finds an area is set at the users and keys; an area still
 * holding documents is not deleted (the backend answers 409, shown by the panel).
 */
@Component({
    selector: "app-knowledge-areas-dialog",
    imports: [
        MatDialogModule,
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        MatFormFieldModule,
        MatInputModule,
        TranslocoPipe,
    ],
    template: `
        <h2 mat-dialog-title>{{ "knowledgeAreas.dialog.title" | transloco }}</h2>
        <mat-dialog-content>
            <p class="hint">{{ "knowledgeAreas.dialog.hint" | transloco }}</p>
            <form class="add" (submit)="$event.preventDefault(); add(newName)">
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                    <mat-label>{{ "knowledgeAreas.dialog.newArea" | transloco }}</mat-label>
                    <input
                        matInput
                        #newName
                        [placeholder]="'knowledgeAreas.dialog.newAreaPlaceholder' | transloco"
                        maxlength="100"
                    />
                </mat-form-field>
                <button mat-flat-button type="submit">
                    <mat-icon>add</mat-icon>
                    {{ "common.create" | transloco }}
                </button>
            </form>
            @if (areas().length === 0) {
                <p class="empty">{{ "knowledgeAreas.dialog.empty" | transloco }}</p>
            }
            <ul class="areas">
                @for (area of areas(); track area.id) {
                    <li>
                        @if (editing() === area.id) {
                            <form class="rename" (submit)="$event.preventDefault(); rename(area, nameInput)">
                                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                                    <mat-label>{{ "common.name" | transloco }}</mat-label>
                                    <input matInput #nameInput [value]="area.name" maxlength="100" />
                                </mat-form-field>
                                <button mat-icon-button type="submit" [matTooltip]="'common.save' | transloco">
                                    <mat-icon>check</mat-icon>
                                </button>
                                <button
                                    mat-icon-button
                                    type="button"
                                    (click)="editing.set(null)"
                                    [matTooltip]="'common.cancel' | transloco"
                                >
                                    <mat-icon>close</mat-icon>
                                </button>
                            </form>
                        } @else {
                            <span class="name">{{ area.name }}</span>
                            <button
                                mat-icon-button
                                (click)="editing.set(area.id)"
                                [matTooltip]="'knowledgeAreas.dialog.rename' | transloco"
                            >
                                <mat-icon>edit</mat-icon>
                            </button>
                            <button
                                mat-icon-button
                                class="danger"
                                (click)="confirmDelete(area)"
                                [matTooltip]="'common.delete' | transloco"
                            >
                                <mat-icon>delete</mat-icon>
                            </button>
                        }
                    </li>
                }
            </ul>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>{{ "common.close" | transloco }}</button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .hint,
            .empty {
                color: var(--app-text-2);
                font-size: 13px;
            }

            .add,
            .rename {
                display: flex;
                align-items: center;
                gap: 8px;
                flex: 1;
            }

            .add {
                margin: 8px 0 12px;
            }

            mat-form-field {
                flex: 1;
                min-width: 0;
            }

            .areas {
                list-style: none;
                margin: 0;
                padding: 0;
                min-width: min(440px, 70vw);
            }

            .areas li {
                display: flex;
                align-items: center;
                gap: 4px;
                padding: 4px 0;
                border-bottom: 1px solid var(--app-border);
            }

            .name {
                flex: 1;
            }

            .danger mat-icon {
                color: var(--app-err);
            }
        `,
    ],
})
export class KnowledgeAreasDialogComponent {
    private store = inject(Store);
    private dialog = inject(MatDialog);
    private transloco = inject(TranslocoService);
    readonly data = inject<KnowledgeAreasDialogData>(MAT_DIALOG_DATA);
    readonly areas = this.store.selectSignal(AreaSelectors.selectAreas);
    /** The area being renamed. */
    readonly editing = signal<number | null>(null);
    private readonly newName = viewChild.required<ElementRef<HTMLInputElement>>("newName");

    constructor() {
        // A new name is cleared and a rename closed once saved - on an error what was typed stays.
        const actions$ = inject(Actions);
        actions$
            .pipe(ofType(AreaActions.renameAreaSuccess), takeUntilDestroyed())
            .subscribe(() => this.editing.set(null));
        actions$
            .pipe(ofType(AreaActions.createAreaSuccess), takeUntilDestroyed())
            .subscribe(() => (this.newName().nativeElement.value = ""));
    }

    add(input: HTMLInputElement): void {
        const name = areaName(input.value);
        if (name === null) {
            return;
        }
        this.store.dispatch(AreaActions.createArea({scope: this.data.scope, name}));
    }

    rename(area: KnowledgeArea, input: HTMLInputElement): void {
        const name = areaName(input.value);
        if (name === null || name === area.name) {
            this.editing.set(null);
            return;
        }
        this.store.dispatch(AreaActions.renameArea({scope: this.data.scope, id: area.id, name}));
    }

    confirmDelete(area: KnowledgeArea): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("knowledgeAreas.dialog.confirmDelete", {name: area.name}),
                    confirmLabel: this.transloco.translate("common.delete"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean))
            .subscribe(() => this.store.dispatch(AreaActions.deleteArea({scope: this.data.scope, id: area.id})));
    }
}
