import {Injectable} from "@angular/core";
import {DefaultTranspiler, TranspileParams} from "@jsverse/transloco";

const PLACEHOLDER = /\{\{\s*([\w.]+)\s*}}/g;

/**
 * Writes the params into a text in one pass. Transloco's own transpiler searches the text again after every
 * replacement, so a value that holds "{{...}}" itself - an area, chat or file name someone typed, an error text of a
 * connected software - lost its braces or, holding its own placeholder ("Team {{name}}"), never ended and froze the
 * tab. A placeholder without a param is left out, as before.
 */
@Injectable()
export class SinglePassTranspiler extends DefaultTranspiler {
    override transpile(input: TranspileParams): unknown {
        const {value, params = {}} = input;
        if (typeof value !== "string") {
            return super.transpile(input);
        }
        return value.replace(PLACEHOLDER, (_, name: string) => {
            const param = params[name];
            return param === undefined || param === null ? "" : String(param);
        });
    }
}
