import {describe, expect, it} from "vitest";
import {Task, TaskValidation} from "../../models/task.model";
import * as TaskActions from "./task.actions";
import {initialState, taskReducer} from "./task.reducer";

const task = {id: 4, name: "Log check", source: "version: 1", steps: []} as unknown as Task;
const validation: TaskValidation = {valid: true, errors: [], steps: []};

describe("taskReducer", () => {
    it("starts afresh for another tenant and ignores a result for the one before", () => {
        const own = taskReducer(initialState, TaskActions.loadTasks({scope: "self"}));
        const other = taskReducer(own, TaskActions.loadTasks({scope: 7}));
        expect(other.scope).toBe(7);
        const late = taskReducer(other, TaskActions.loadTasksSuccess({scope: "self", tasks: [task]}));
        expect(late.tasks).toEqual([]);
    });

    it("opens a new task with its draft and forgets the one before", () => {
        let state = taskReducer(initialState, TaskActions.openTaskSuccess({scope: "self", task}));
        state = taskReducer({...state, scope: "self"}, TaskActions.newTask({scope: "self", source: "version: 1"}));
        expect(state.current).toBeNull();
        expect(state.draft).toBe("version: 1");
        expect(state.validation).toBeNull();
    });

    it("keeps the saved task and drops the draft", () => {
        let state = taskReducer(initialState, TaskActions.newTask({scope: "self", source: "x"}));
        state = taskReducer(state, TaskActions.saveTask({scope: "self", taskId: null, request: {source: "x"}}));
        expect(state.saving).toBe(true);
        state = taskReducer(state, TaskActions.saveTaskSuccess({scope: "self", task}));
        expect(state).toMatchObject({current: task, draft: null, saving: false});
    });

    it("keeps a check's result and the runs of the task open only", () => {
        let state = taskReducer(
            {...initialState, scope: "self", current: task},
            TaskActions.validateTaskSuccess({scope: "self", validation}),
        );
        expect(state.validation).toBe(validation);
        state = taskReducer(state, TaskActions.loadRunsSuccess({scope: "self", taskId: 99, runs: [{id: 1} as never]}));
        expect(state.runs).toEqual([]);
    });
});
