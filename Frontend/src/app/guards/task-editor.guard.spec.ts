import {TestBed} from "@angular/core/testing";
import {ActivatedRouteSnapshot, RouterStateSnapshot} from "@angular/router";
import {provideMockStore} from "@ngrx/store/testing";
import {Observable, of} from "rxjs";
import {describe, expect, it, vi} from "vitest";
import {TaskEditorComponent} from "../components/task-editor/task-editor.component";
import {initialState} from "../store/auth/auth.reducer";
import {taskEditorGuard} from "./task-editor.guard";

describe("taskEditorGuard", () => {
    function leave(authenticated: boolean, editorAnswer: boolean): {answer: boolean | undefined; asked: boolean} {
        TestBed.configureTestingModule({
            providers: [provideMockStore({initialState: {auth: {...initialState, isAuthenticated: authenticated}}})],
        });
        const mayLeave = vi.fn(() => of(editorAnswer));
        const editor = {mayLeave} as unknown as TaskEditorComponent;
        const route = {} as ActivatedRouteSnapshot;
        const state = {} as RouterStateSnapshot;
        const result = TestBed.runInInjectionContext(() =>
            taskEditorGuard(editor, route, state, state),
        ) as Observable<boolean>;
        let answer: boolean | undefined;
        result.subscribe(value => (answer = value));
        return {answer, asked: mayLeave.mock.calls.length > 0};
    }

    it("asks the editor while the user is signed in - and takes its no", () => {
        expect(leave(true, false)).toEqual({answer: false, asked: true});
    });

    it("asks the editor while the user is signed in - and takes its yes", () => {
        expect(leave(true, true)).toEqual({answer: true, asked: true});
    });

    it("leaves without asking once the session is over - a logout, an expired token", () => {
        expect(leave(false, false)).toEqual({answer: true, asked: false});
    });
});
