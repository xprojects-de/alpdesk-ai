import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {AccessRequestService} from "../../services/access-request.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as AccessRequestActions from "./access-request.actions";

@Injectable()
export class AccessRequestEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private accessRequestService = inject(AccessRequestService);

    loadAccessRequests$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AccessRequestActions.loadAccessRequests),
            switchMap(() =>
                this.accessRequestService.getAll().pipe(
                    map(requests => AccessRequestActions.loadAccessRequestsSuccess({requests})),
                    catchError(error =>
                        of(
                            AccessRequestActions.loadAccessRequestsFailure({
                                error: this.errors.of(error, "errors.accessRequest.loadAccessRequests"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    assignAccessRequest$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AccessRequestActions.assignAccessRequest),
            concatMap(({id, assignment}) =>
                this.accessRequestService.assign(id, assignment).pipe(
                    map(() => AccessRequestActions.assignAccessRequestSuccess({id})),
                    catchError(error =>
                        of(
                            AccessRequestActions.assignAccessRequestFailure({
                                error: this.errors.of(error, "errors.accessRequest.assignAccessRequest"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );

    rejectAccessRequest$ = createEffect(() =>
        this.actions$.pipe(
            ofType(AccessRequestActions.rejectAccessRequest),
            concatMap(({id}) =>
                this.accessRequestService.reject(id).pipe(
                    map(() => AccessRequestActions.rejectAccessRequestSuccess({id})),
                    catchError(error =>
                        of(
                            AccessRequestActions.rejectAccessRequestFailure({
                                error: this.errors.of(error, "errors.accessRequest.rejectAccessRequest"),
                            }),
                        ),
                    ),
                ),
            ),
        ),
    );
}
