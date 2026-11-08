package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/**
 * A unit's whole state: its tasks, what its controller is doing for them, and whether the controller
 * holds the posture's activity set aside.
 */
@NullMarked
public record UnitState(TaskSlots tasks, Execution execution, boolean postureSuspended) {
    public UnitState(TaskSlots tasks) {
        this(tasks, Execution.UNSTARTED, false);
    }

    public UnitState withTasks(TaskSlots next) {
        return new UnitState(next, execution, postureSuspended);
    }

    public UnitState withExecution(Execution next) {
        return new UnitState(tasks, next, postureSuspended);
    }

    /**
     * What the controller is carrying out, which can still be for a task the unit has moved on from
     * while a committed activity finishes.
     */
    public sealed interface Execution {
        Execution UNSTARTED = new Unstarted();
        Execution PERFORMING = new Performing();
        Execution GONE = new Gone();

        /** Nothing has run yet. */
        record Unstarted() implements Execution {
        }

        /**
         * An activity runs for the task, and has got as far as the progress says. The task is told
         * apart from others by identity, never by equals: an equal order given again, or an equal
         * posture after mounting the same ship seat, is a new task with a new activity.
         */
        record Running(Task task, Activity.State progress) implements Execution {
        }

        /** A world operation is being carried out, and its report is awaited. */
        record Performing() implements Execution {
        }

        /** The unit has left the world, and nothing it is told matters any more. */
        record Gone() implements Execution {
        }
    }
}
