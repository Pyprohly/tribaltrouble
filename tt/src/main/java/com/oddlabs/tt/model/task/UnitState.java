package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A unit's whole state: its tasks, what its controller is doing for them, and the posture whose
 * activity is set aside while something above the posture runs. The parked posture, when there is
 * one, is always the unit's posture and never the task that is running.
 */
@NullMarked
public record UnitState(TaskStack tasks, Slot slot, @Nullable Posture parked) {
    public UnitState(TaskStack tasks) {
        this(tasks, Slot.VACANT, null);
    }

    public UnitState withTasks(TaskStack next) {
        return new UnitState(next, slot, parked);
    }

    /** What the controller is doing for the unit's tasks. */
    public sealed interface Slot {
        Slot VACANT = new Vacant();
        Slot PERFORMING = new Performing();
        Slot GONE = new Gone();

        /** Nothing has run yet. */
        record Vacant() implements Slot {
        }

        /** An activity runs for the task, and has got as far as the progress says. */
        record Running(Task task, Activity.State progress) implements Slot {
        }

        /** A world operation is being carried out, and its report is awaited. */
        record Performing() implements Slot {
        }

        /** The unit has left the world, and nothing it is told matters any more. */
        record Gone() implements Slot {
        }
    }
}
