package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** A unit's whole state: its tasks, and what its controller is doing for them. */
@NullMarked
public record UnitState(
                        TaskSlots tasks,
                        Execution execution
) {
    public UnitState(TaskSlots tasks) {
        this(tasks, Execution.UNSTARTED);
    }

    public UnitState withTasks(TaskSlots next) {
        return new UnitState(next, execution);
    }

    public UnitState withExecution(Execution next) {
        return new UnitState(tasks, next);
    }

    /**
     * What the controller is carrying out, which can still be for a task the unit has moved on from
     * while a committed activity finishes.
     */
    public sealed interface Execution {
        Execution UNSTARTED = new Unstarted();
        Execution GONE = new Gone();

        /** Nothing has run yet. */
        record Unstarted() implements Execution {
        }

        /**
         * An activity runs for the task, and has got as far as the progress says. The task is told
         * apart from others by identity, never by equals: an equal order given again, or an equal
         * posture after mounting the same ship seat, is a new task with a new activity. An activity
         * left to finish ran for an order the unit was excused from; the unit decides once it is done,
         * not when it next yields.
         */
        record Running(Task task, Activity.State progress, boolean leftToFinish) implements Execution {
            public Running(Task task, Activity.State progress) {
                this(task, progress, false);
            }

            public Running withProgress(Activity.State next) {
                return new Running(task, next, leftToFinish);
            }

            public Running withLeftToFinish(boolean next) {
                return new Running(task, progress, next);
            }
        }

        /**
         * A world operation is being carried out, and its report is awaited. The operation is told apart
         * from others by identity, as a task is: depositing into the same store on the next trip is a new
         * operation, and only its own report ends it.
         */
        record Performing(UnitPlan.Operation operation) implements Execution {
        }

        /** The unit has left the world, and nothing it is told matters any more. */
        record Gone() implements Execution {
        }
    }
}
