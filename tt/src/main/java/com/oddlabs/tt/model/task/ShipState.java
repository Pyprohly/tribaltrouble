package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** A ship's whole state: its task, and what its controller is doing for it. */
@NullMarked
public record ShipState(Task task, Slot slot) {
    public ShipState(Task task) {
        this(task, Slot.VACANT);
    }

    /** What the controller is doing for the ship's task. */
    public enum Slot {
        /** Nothing has run yet. */
        VACANT,
        /** An activity runs for the task. */
        RUNNING,
        /** A world operation is being carried out, and its report is awaited. */
        PERFORMING
    }
}
