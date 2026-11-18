package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** A ship's whole state: its task, and what its controller is doing for it. */
@NullMarked
public record ShipState(Task task, Execution execution) {
    public ShipState(Task task) {
        this(task, Execution.UNSTARTED);
    }

    /** What the controller is carrying out for the ship's task. */
    public sealed interface Execution {
        Execution UNSTARTED = new Unstarted();
        Execution RUNNING = new Running();

        /** Nothing has run yet. */
        record Unstarted() implements Execution {
        }

        /** An activity runs for the task. */
        record Running() implements Execution {
        }

        /**
         * A world operation is being carried out, and its report is awaited. The operation is told apart
         * from others by identity, so only its own report ends it.
         */
        record Performing(ShipPlan.Operation operation) implements Execution {
        }
    }
}
