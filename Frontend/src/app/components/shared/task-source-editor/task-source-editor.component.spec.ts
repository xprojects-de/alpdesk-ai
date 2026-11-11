import {Component, signal} from "@angular/core";
import {ComponentFixture, TestBed} from "@angular/core/testing";
import {By} from "@angular/platform-browser";
import {beforeEach, describe, expect, it} from "vitest";
import {TaskSourceEditorComponent} from "./task-source-editor.component";

@Component({
    imports: [TaskSourceEditorComponent],
    template: `<app-task-source-editor
        [source]="source()"
        [errorLines]="errorLines()"
        [changedLines]="changedLines()"
        [range]="range()"
        [label]="'Task'"
        (changed)="text.set($event)"
    />`,
})
class HostComponent {
    readonly source = signal("version: 1\nname: Log check\n");
    readonly errorLines = signal<number[]>([]);
    readonly changedLines = signal<number[]>([]);
    readonly range = signal<{from: number; to: number} | null>(null);
    readonly text = signal("");
}

/** The CodeMirror view behind the component - what the user sees. */
interface EditorView {
    state: {doc: {toString(): string}};
    dispatch(transaction: {changes: {from: number; insert: string}}): void;
}

describe("TaskSourceEditorComponent", () => {
    let fixture: ComponentFixture<HostComponent>;
    let host: HostComponent;
    let view: () => EditorView;

    beforeEach(async () => {
        await TestBed.configureTestingModule({imports: [HostComponent]}).compileComponents();
        fixture = TestBed.createComponent(HostComponent);
        host = fixture.componentInstance;
        fixture.detectChanges();
        const editor = fixture.debugElement.query(By.directive(TaskSourceEditorComponent))
            .componentInstance as unknown as {view: EditorView};
        view = () => editor.view;
    });

    const typeAtStart = (text: string) => view().dispatch({changes: {from: 0, insert: text}});

    it("shows the text and reports every change", () => {
        expect(view().state.doc.toString()).toBe("version: 1\nname: Log check\n");

        typeAtStart("# a comment\n");

        expect(host.text()).toBe("# a comment\nversion: 1\nname: Log check\n");
    });

    it("keeps an edit when the marks change right after the view was built (a draft, then a check)", () => {
        typeAtStart("x");

        host.errorLines.set([2]);
        host.changedLines.set([1]);
        host.range.set({from: 1, to: 1});
        fixture.detectChanges();

        expect(view().state.doc.toString()).toBe("xversion: 1\nname: Log check\n");
        expect(host.text()).toBe("xversion: 1\nname: Log check\n");
    });

    it("keeps an edit when the marks change again later", () => {
        host.errorLines.set([1]);
        fixture.detectChanges();
        typeAtStart("x");

        host.range.set({from: 2, to: 2});
        fixture.detectChanges();
        host.errorLines.set([2]);
        fixture.detectChanges();

        expect(view().state.doc.toString()).toBe("xversion: 1\nname: Log check\n");
    });

    it("shows a new text from outside - another task opened, a draft", () => {
        typeAtStart("x");

        host.source.set("version: 1\nname: Drafted\n");
        fixture.detectChanges();

        expect(view().state.doc.toString()).toBe("version: 1\nname: Drafted\n");
    });

    it("leaves the view alone when the text from outside is what it shows already", () => {
        const before = view();

        host.source.set("version: 1\nname: Log check\n");
        fixture.detectChanges();

        expect(view()).toBe(before);
    });
});
