package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/**
 * What a unit's transition asks its controller to do, in the order given. The controller keeps a
 * posture's activity for as long as the posture lasts, so stopping it sets it aside and starting the
 * same posture again resumes it with its timers and boarding progress intact.
 */
@NullMarked
public sealed interface UnitCommand {
    Stop INTERRUPT = new Interrupt();
    Stop RELEASE = new Release();
    UnitCommand DISPOSE = new Dispose();

    /** Ends the running activity before the next plan starts. */
    sealed interface Stop extends UnitCommand {
    }

    /** Interrupts the running activity: cancels it, or sets it aside if it is the posture's. */
    record Interrupt() implements Stop {
    }

    /** Releases the running activity without cancelling it, because it has finished. */
    record Release() implements Stop {
    }

    /**
     * Starts an activity for the plan, or resumes the posture's when the task is the posture it was
     * started for. Starting a new posture cancels the old posture's activity.
     */
    record Run(Task task, UnitPlan.Run run) implements UnitCommand {
    }

    /**
     * Carries out the operation, then reports {@link Event.Report.Performed}, or {@link Event.Report.Removed} if the
     * unit left the world.
     */
    record Perform(UnitPlan.Operation operation) implements UnitCommand {
    }

    /** Disposes of every activity of a unit that has left the world, cancelling the posture's that was set aside. */
    record Dispose() implements UnitCommand {
    }
}
