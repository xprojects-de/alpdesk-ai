import {Component, computed, DestroyRef, DOCUMENT, inject, input, output} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {Clipboard} from "@angular/cdk/clipboard";
import {MatButtonModule} from "@angular/material/button";
import {MatIconModule} from "@angular/material/icon";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MatDialog} from "@angular/material/dialog";
import {MatSnackBar} from "@angular/material/snack-bar";
import {TranslocoPipe, TranslocoService} from "@jsverse/transloco";
import {filter} from "rxjs/operators";
import {ConfirmDialogComponent, ConfirmDialogData} from "../confirm-dialog/confirm-dialog.component";
import {loginAddress} from "../../../utils/login-page.util";

/**
 * The address of a tenant's own login page, to copy and hand on - the admin's tenant page and the tenant admins'
 * page "Benutzer" show it. With renewable a new address can be made (the admin's); the old one then shows /login.
 */
@Component({
    selector: "app-login-link",
    imports: [MatButtonModule, MatIconModule, MatTooltipModule, TranslocoPipe],
    template: `
        <div class="link">
            <span class="address mono">{{ address() }}</span>
            <button
                mat-icon-button
                type="button"
                (click)="copy()"
                [matTooltip]="'branding.loginLink.copy' | transloco"
                [attr.aria-label]="'branding.loginLink.copy' | transloco"
            >
                <mat-icon>content_copy</mat-icon>
            </button>
        </div>
        @if (renewable()) {
            <button mat-button type="button" class="renew" (click)="confirmRenew()">
                <mat-icon>autorenew</mat-icon>
                {{ "branding.loginLink.renew" | transloco }}
            </button>
        }
    `,
    styles: [
        `
            :host {
                display: flex;
                align-items: center;
                gap: 8px;
                flex-wrap: wrap;
                min-width: 0;
            }

            .link {
                display: flex;
                align-items: center;
                gap: 4px;
                min-width: 0;
                max-width: 100%;
                padding-left: 12px;
                border: 1px solid var(--app-border);
                border-radius: 8px;
                background: var(--app-surface-2);
            }

            .address {
                overflow-wrap: anywhere;
                font-size: 13px;
            }
        `,
    ],
})
export class LoginLinkComponent {
    private clipboard = inject(Clipboard);
    private snackBar = inject(MatSnackBar);
    private dialog = inject(MatDialog);
    private transloco = inject(TranslocoService);
    private destroyRef = inject(DestroyRef);
    private document = inject(DOCUMENT);

    readonly loginKey = input.required<string>();
    readonly renewable = input(false);
    readonly renew = output<void>();

    readonly address = computed(() => loginAddress(this.document.baseURI, this.loginKey()));

    copy(): void {
        const copied = this.clipboard.copy(this.address());
        this.snackBar.open(
            this.transloco.translate(copied ? "branding.snack.linkCopied" : "branding.snack.linkCopyFailed"),
            this.transloco.translate("common.ok"),
            {duration: 3000},
        );
    }

    confirmRenew(): void {
        this.dialog
            .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
                width: "450px",
                data: {
                    message: this.transloco.translate("branding.loginLink.confirmRenew"),
                    confirmLabel: this.transloco.translate("branding.loginLink.renew"),
                    confirmColor: "warn",
                },
            })
            .afterClosed()
            .pipe(filter(Boolean), takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.renew.emit());
    }
}
