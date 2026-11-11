import {TestBed} from "@angular/core/testing";
import {Store} from "@ngrx/store";
import {vi} from "vitest";
import {App} from "./app";
import * as AuthActions from "./store/auth/auth.actions";

describe("App", () => {
    let dispatchSpy: ReturnType<typeof vi.fn>;

    beforeEach(async () => {
        dispatchSpy = vi.fn();
        await TestBed.configureTestingModule({
            imports: [App],
            providers: [{provide: Store, useValue: {dispatch: dispatchSpy}}],
        }).compileComponents();
    });

    it("should create the app", () => {
        const fixture = TestBed.createComponent(App);
        const app = fixture.componentInstance;
        expect(app).toBeTruthy();
    });

    it("should dispatch checkAuth on init", () => {
        const fixture = TestBed.createComponent(App);
        fixture.detectChanges();
        expect(dispatchSpy).toHaveBeenCalledWith(AuthActions.checkAuth());
    });

    it("should render a router outlet", () => {
        const fixture = TestBed.createComponent(App);
        fixture.detectChanges();
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector("router-outlet")).toBeTruthy();
    });
});
