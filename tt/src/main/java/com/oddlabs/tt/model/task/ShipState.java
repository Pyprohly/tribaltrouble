package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** A ship's whole state: its task, and what its controller is doing for it. */
@NullMarked
public record ShipState(Task task, Execution execution) {
    public ShipState(Task task) {
        this(task, Execution.UNSTARTED);
    }

    /** What the controller is carrying out for the ship's task. */
    public enum Execution {
        /** Nothing has run yet. */
        UNSTARTED,
        /** An activity runs for the task. */
        RUNNING,
        /** A world operation is being carried out, and its report is awaited. */
        PERFORMING
    }
}
