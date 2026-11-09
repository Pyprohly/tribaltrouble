package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/**
 * What a unit's transition asks its controller to do, in the order given. The controller keeps a
 * posture's activity for as long as the posture lasts: stopping it only sets it aside, finished or
 * not, and running the posture again picks it up with its timers and boarding progress intact.
 */
@NullMarked
public sealed interface UnitCommand {
    Stop INTERRUPT = new Interrupt();
    Stop RELEASE = new Release();
    UnitCommand DISCARD = new Discard();

    /** Ends the running activity before the next plan starts. */
    sealed interface Stop extends UnitCommand {
    }

    /** Cancels the running activity, or sets it aside if it is the posture's. */
    record Interrupt() implements Stop {
    }

    /** Releases the running activity without cancelling it, because it has finished. */
    record Release() implements Stop {
    }

    /**
     * Cancels the posture's activity because the posture was replaced or the unit left the world;
     * does nothing if the posture's plan never ran, since the activity is only built then. If that
     * activity is running, a {@link Stop} must come earlier in the same commands, so it is no
     * longer running when it gets discarded.
     */
    record Discard() implements UnitCommand {
    }

    /** Starts a new activity for the plan, or runs the posture's again when the controller holds it. */
    record Run(UnitPlan.Run run) implements UnitCommand {
    }

    /**
     * Carries out the operation, then reports {@link Event.Report.Performed}, or {@link Event.Report.Removed} if the
     * unit left the world.
     */
    record Perform(UnitPlan.Operation operation) implements UnitCommand {
    }
}
