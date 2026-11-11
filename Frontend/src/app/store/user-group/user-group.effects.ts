import {inject, Injectable} from "@angular/core";
import {Actions, createEffect, ofType} from "@ngrx/effects";
import {of} from "rxjs";
import {catchError, concatMap, map, switchMap} from "rxjs/operators";
import {UserGroupService} from "../../services/user-group.service";
import {ErrorMessages} from "../../i18n/error-messages.service";
import * as GroupActions from "./user-group.actions";

@Injectable()
export class UserGroupEffects {
    private actions$ = inject(Actions);
    private errors = inject(ErrorMessages);
    private groupService = inject(UserGroupService);

    load$ = createEffect(() =>
        this.actions$.pipe(
            ofType(GroupActions.loadGroups),
            switchMap(({scope}) =>
                this.groupService.getAll(scope).pipe(
                    map(groups => GroupActions.loadGroupsSuccess({scope, groups})),
                    catchError(error =>
                        of(GroupActions.loadGroupsFailure({error: this.errors.of(error, "errors.group.load")})),
                    ),
                ),
            ),
        ),
    );

    create$ = createEffect(() =>
        this.actions$.pipe(
            ofType(GroupActions.createGroup),
            concatMap(({scope, group}) =>
                this.groupService.create(scope, group).pipe(
                    map(created => GroupActions.createGroupSuccess({scope, group: created})),
                    catchError(error =>
                        of(GroupActions.createGroupFailure({error: this.errors.of(error, "errors.group.create")})),
                    ),
                ),
            ),
        ),
    );

    update$ = createEffect(() =>
        this.actions$.pipe(
            ofType(GroupActions.updateGroup),
            concatMap(({scope, id, group}) =>
                this.groupService.update(scope, id, group).pipe(
                    map(updated => GroupActions.updateGroupSuccess({scope, group: updated})),
                    catchError(error =>
                        of(GroupActions.updateGroupFailure({error: this.errors.of(error, "errors.group.update")})),
                    ),
                ),
            ),
        ),
    );

    delete$ = createEffect(() =>
        this.actions$.pipe(
            ofType(GroupActions.deleteGroup),
            concatMap(({scope, id}) =>
                this.groupService.delete(scope, id).pipe(
                    map(() => GroupActions.deleteGroupSuccess({scope, id})),
                    catchError(error =>
                        of(GroupActions.deleteGroupFailure({error: this.errors.of(error, "errors.group.delete")})),
                    ),
                ),
            ),
        ),
    );
}
