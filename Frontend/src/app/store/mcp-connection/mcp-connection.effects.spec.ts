import {TestBed} from "@angular/core/testing";
import {provideMockActions} from "@ngrx/effects/testing";
import {Action} from "@ngrx/store";
import {Subject} from "rxjs";
import {McpConnectionService} from "../../services/mcp-connection.service";
import {McpConnection} from "../../models/mcp.model";
import {translocoTesting} from "../../i18n/i18n.testing";
import {TenantScope} from "../../models/tenant.model";
import * as AccountActions from "../account/account.actions";
import * as McpConnectionActions from "./mcp-connection.actions";
import {McpConnectionEffects} from "./mcp-connection.effects";

describe("McpConnectionEffects", () => {
    function setup() {
        const actions$ = new Subject<Action>();
        TestBed.configureTestingModule({
            imports: [translocoTesting("de")],
            providers: [
                McpConnectionEffects,
                provideMockActions(() => actions$),
                {provide: McpConnectionService, useValue: {}},
            ],
        });
        const emitted: Action[] = [];
        TestBed.inject(McpConnectionEffects).reloadAccountAfterChange$.subscribe(action => emitted.push(action));
        return {actions$, emitted};
    }

    const connection = {id: 3} as McpConnection;

    function changes(scope: TenantScope): Action[] {
        return [
            McpConnectionActions.createConnectionSuccess({scope, connection}),
            McpConnectionActions.updateConnectionSuccess({scope, connection}),
            McpConnectionActions.deleteConnectionSuccess({scope, connectionId: 3}),
        ];
    }

    describe("the account after a change of the connected software", () => {
        it("is read again for a tenant admin - whether an agent answers their chat may have changed", () => {
            const {actions$, emitted} = setup();

            changes("self").forEach(action => actions$.next(action));

            expect(emitted).toEqual([
                AccountActions.loadAccount(),
                AccountActions.loadAccount(),
                AccountActions.loadAccount(),
            ]);
        });

        it("stays as it is for the admin, who changes another tenant's software and has no account", () => {
            const {actions$, emitted} = setup();

            changes(34).forEach(action => actions$.next(action));

            expect(emitted).toEqual([]);
        });
    });
});
