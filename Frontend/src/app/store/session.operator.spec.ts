import {Actions, ofType} from "@ngrx/effects";
import {Action, createAction, props} from "@ngrx/store";
import {Subject} from "rxjs";
import {concatMap} from "rxjs/operators";
import {endedByLogout} from "./session.operator";
import * as AuthActions from "./auth/auth.actions";

describe("endedByLogout", () => {
    const work = createAction("[Test] Work", props<{id: string}>());

    it("ends what runs and drops what waits when the user logs out - the next session works again", () => {
        const dispatched = new Subject<Action>();
        const actions$ = new Actions(dispatched);
        const replies: Record<string, Subject<string>> = {};
        const results: string[] = [];
        actions$
            .pipe(
                ofType(work),
                concatMap(({id}) => (replies[id] = new Subject<string>())),
                endedByLogout(actions$),
            )
            .subscribe(result => results.push(result));

        dispatched.next(work({id: "a"}));
        dispatched.next(work({id: "b"}));
        dispatched.next(AuthActions.logout());
        replies["a"].next("a");

        expect(results).toEqual([]);
        expect(replies["b"]).toBeUndefined();

        dispatched.next(work({id: "c"}));
        replies["c"].next("c");

        expect(results).toEqual(["c"]);
    });

    it("ends what waits at the switch to another tenant - the new token is stored before the page reloads", () => {
        const dispatched = new Subject<Action>();
        const actions$ = new Actions(dispatched);
        const replies: Record<string, Subject<string>> = {};
        actions$
            .pipe(
                ofType(work),
                concatMap(({id}) => (replies[id] = new Subject<string>())),
                endedByLogout(actions$),
            )
            .subscribe();

        dispatched.next(work({id: "a"}));
        dispatched.next(work({id: "b"}));
        dispatched.next(AuthActions.switchTenantSuccess({response: {access_token: "neu"}}));
        replies["a"].next("a");
        replies["a"].complete();

        expect(replies["b"]).toBeUndefined();
    });
});
