package de.alpdesk.entities.task;

/** Where a task's run stands. */
public enum RunState {
    /** Waiting for a free place on the task executor. */
    QUEUED,
    RUNNING,
    /** Ran through - whether it found something is in its steps. */
    DONE,
    /** Ran through, but a finding stayed unclear when an edge back reached its limit - never "nothing found". */
    UNCLEAR,
    FAILED,
    /** Cancelled by a tenant admin or the admin - before its next step or round. */
    CANCELLED;

    public boolean open() {
        return this == QUEUED || this == RUNNING;
    }
}
