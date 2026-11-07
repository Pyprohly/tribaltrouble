package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/**
 * What a unit's transition asks its controller to do, in the order given. The controller keeps a
 * posture's activity for as long as the posture lasts, so stopping it sets it aside and starting the
 * same posture again resumes it with its timers and boarding progress intact.
 */
@NullMarked
public sealed interface UnitCommand {
    UnitCommand CANCEL = new Cancel();
    UnitCommand RELEASE = new Release();
    UnitCommand LEAVE = new Leave();

    /** Cancels the running activity, unless it is a posture's. */
    record Cancel() implements UnitCommand {
    }

    /** Drops the running activity without cancelling it, because it has finished. */
    record Release() implements UnitCommand {
    }

    /**
     * Starts an activity for the plan, or resumes the posture's when the task is the posture it was
     * started for. Starting a new posture cancels the old posture's activity.
     */
    record Start(Task task, UnitPlan.Run run) implements UnitCommand {
    }

    /**
     * Carries out the operation, then reports {@link Event.Report.Performed}, or {@link Event.Report.Removed} if the
     * unit left the world.
     */
    record Perform(UnitPlan.Operation operation) implements UnitCommand {
    }

    /** Drops every activity of a unit that has left the world, cancelling a posture's that was set aside. */
    record Leave() implements UnitCommand {
    }
}
