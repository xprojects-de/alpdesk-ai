import {Component, effect, ElementRef, input, viewChild} from "@angular/core";
import {isDocSiteUrl} from "../../../utils/doc-site.util";
import {environment} from "../../../../environments/environment";

/**
 * A handbook's website in an iframe. Sandboxed twice: by the iframe here and by the policy every file is served with,
 * so its scripts never reach the app (docs/architecture/doc-sites.md "Security"). Only an address of a website below
 * the API's base is shown.
 */
@Component({
    selector: "app-doc-site-frame",
    template: `<iframe
        #frame
        [title]="title()"
        sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"
        referrerpolicy="no-referrer"
    ></iframe>`,
    styles: [
        `
            /* Fills whatever room its host is given - a flex item or a box of fixed height alike. */
            :host {
                display: block;
                position: relative;
            }

            iframe {
                position: absolute;
                inset: 0;
                width: 100%;
                height: 100%;
                border: 0;
                background: var(--app-doc-site-bg);
            }
        `,
    ],
})
export class DocSiteFrameComponent {
    readonly url = input.required<string>();
    readonly title = input.required<string>();
    private readonly frame = viewChild.required<ElementRef<HTMLIFrameElement>>("frame");

    constructor() {
        effect(() => {
            const url = this.url();
            if (isDocSiteUrl(url, environment.apiUrl)) {
                this.frame().nativeElement.src = url;
            }
        });
    }
}
