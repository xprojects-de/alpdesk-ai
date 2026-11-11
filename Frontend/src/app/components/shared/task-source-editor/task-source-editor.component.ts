import {Component, effect, ElementRef, input, OnDestroy, output, untracked, viewChild} from "@angular/core";
import {defaultKeymap, history, historyKeymap, indentWithTab} from "@codemirror/commands";
import {yaml} from "@codemirror/lang-yaml";
import {HighlightStyle, indentUnit, syntaxHighlighting} from "@codemirror/language";
import {EditorState, RangeSetBuilder, StateEffect, StateField} from "@codemirror/state";
import {Decoration, DecorationSet, EditorView, keymap, lineNumbers} from "@codemirror/view";
import {tags} from "@lezer/highlight";

/** The lines to mark: a mistake's, those a draft changed, and those of the step chosen in the graph. */
interface Marks {
    errorLines: number[];
    changedLines: number[];
    range: {from: number; to: number} | null;
}

const setMarks = StateEffect.define<Marks>();

/** Line decorations for the marks - rebuilt whenever they are set. */
const marksField = StateField.define<DecorationSet>({
    create: () => Decoration.none,
    update(decorations, transaction) {
        let current = decorations.map(transaction.changes);
        for (const effect of transaction.effects) {
            if (effect.is(setMarks)) {
                current = decorate(transaction.state, effect.value);
            }
        }
        return current;
    },
    provide: field => EditorView.decorations.from(field),
});

function decorate(state: EditorState, marks: Marks): DecorationSet {
    const builder = new RangeSetBuilder<Decoration>();
    const lines = state.doc.lines;
    const errors = new Set(marks.errorLines.filter(line => line >= 1 && line <= lines));
    const changed = new Set(marks.changedLines);
    for (let number = 1; number <= lines; number++) {
        const classes = [];
        if (marks.range && number >= marks.range.from && number <= marks.range.to) {
            classes.push("cm-step-line");
        }
        if (changed.has(number)) {
            classes.push("cm-changed-line");
        }
        if (errors.has(number)) {
            classes.push("cm-error-line");
        }
        if (classes.length > 0) {
            builder.add(
                state.doc.line(number).from,
                state.doc.line(number).from,
                Decoration.line({class: classes.join(" ")}),
            );
        }
    }
    return builder.finish();
}

/** YAML in the app's code colours (the --app-code-* tokens, light and dark). */
const highlighting = HighlightStyle.define([
    {tag: [tags.propertyName, tags.definition(tags.propertyName)], color: "var(--app-code-title)"},
    {tag: [tags.string, tags.special(tags.string)], color: "var(--app-code-string)"},
    {tag: [tags.number, tags.bool, tags.null], color: "var(--app-code-number)"},
    {tag: tags.comment, color: "var(--app-code-comment)", fontStyle: "italic"},
    {tag: [tags.keyword, tags.typeName], color: "var(--app-code-keyword)"},
    {tag: [tags.punctuation, tags.separator, tags.bracket], color: "var(--app-text-2)"},
]);

const theme = EditorView.theme({
    "&": {
        color: "var(--app-text)",
        backgroundColor: "var(--app-surface)",
        fontSize: "13px",
        height: "100%",
    },
    ".cm-content": {fontFamily: "var(--app-mono)", caretColor: "var(--app-text)"},
    ".cm-gutters": {
        backgroundColor: "var(--app-surface-2)",
        color: "var(--app-text-3)",
        borderRight: "1px solid var(--app-border)",
    },
    ".cm-activeLine": {backgroundColor: "transparent"},
    "&.cm-focused": {outline: "none"},
    ".cm-step-line": {backgroundColor: "var(--app-brand-softer)"},
    ".cm-changed-line": {boxShadow: "inset 3px 0 0 var(--app-info)"},
    ".cm-error-line": {backgroundColor: "color-mix(in oklab, var(--app-err) 14%, transparent)"},
    ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": {backgroundColor: "var(--app-brand-soft)"},
});

/**
 * The text of a task, edited in CodeMirror 6 with YAML's syntax (docs/architecture/tasks.md "The editor"): it reports
 * every change and the line the cursor goes to, and marks a mistake's lines and the step chosen in the graph.
 */
@Component({
    selector: "app-task-source-editor",
    template: `<div #host class="host"></div>`,
    styles: [
        `
            :host {
                display: block;
                border: 1px solid var(--app-border);
                border-radius: 8px;
                overflow: hidden;
            }

            .host {
                height: 100%;
                min-height: 480px;
            }
        `,
    ],
})
export class TaskSourceEditorComponent implements OnDestroy {
    /** The text it starts with - and is set to when it changes from outside (another task opened). */
    readonly source = input.required<string>();
    readonly errorLines = input<number[]>([]);
    /** Lines a draft wrote new - marked until the text is saved or another draft comes. */
    readonly changedLines = input<number[]>([]);
    readonly range = input<{from: number; to: number} | null>(null);
    readonly label = input.required<string>();
    readonly changed = output<string>();
    readonly lineChosen = output<number>();

    private readonly host = viewChild.required<ElementRef<HTMLElement>>("host");
    private view: EditorView | null = null;

    constructor() {
        // Only the text from outside rebuilds the view. Building it reads the marks too - tracked, they would rebuild
        // it on the next mark while the text is edited, and the edit would be lost (the view goes back to source).
        effect(() => {
            const source = this.source();
            untracked(() => this.show(source));
        });
        effect(() => {
            const marks = {errorLines: this.errorLines(), changedLines: this.changedLines(), range: this.range()};
            this.view?.dispatch({effects: setMarks.of(marks)});
        });
    }

    ngOnDestroy(): void {
        this.view?.destroy();
    }

    private show(source: string): void {
        if (this.view && this.view.state.doc.toString() === source) {
            return;
        }
        this.view?.destroy();
        this.view = new EditorView({
            parent: this.host().nativeElement,
            state: EditorState.create({
                doc: source,
                extensions: [
                    lineNumbers(),
                    history(),
                    indentUnit.of("  "),
                    keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
                    yaml(),
                    syntaxHighlighting(highlighting),
                    theme,
                    marksField,
                    EditorView.contentAttributes.of({"aria-label": this.label()}),
                    EditorView.updateListener.of(update => {
                        if (update.docChanged) {
                            this.changed.emit(update.state.doc.toString());
                        }
                        if (update.selectionSet) {
                            this.lineChosen.emit(update.state.doc.lineAt(update.state.selection.main.head).number);
                        }
                    }),
                ],
            }),
        });
        this.view.dispatch({
            effects: setMarks.of({
                errorLines: this.errorLines(),
                changedLines: this.changedLines(),
                range: this.range(),
            }),
        });
    }
}
