package de.alpdesk.entities.task;

/** What became of one step in a run. */
public enum StepState {
    DONE,
    /** Its condition was false, or every step it takes its input from was skipped. */
    SKIPPED,
    FAILED
}
