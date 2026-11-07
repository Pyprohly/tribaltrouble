package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/**
 * What a unit's transition asks its controller to do, in the order given. The controller holds the
 * running and the parked activity; these commands are all it does with them.
 */
@NullMarked
public sealed interface UnitCommand {
    UnitCommand CANCEL = new Cancel();
    UnitCommand PARK = new Park();
    UnitCommand RELEASE = new Release();
    UnitCommand DISCARD = new Discard();
    UnitCommand RESUME = new Resume();

    /** Cancels the running activity. */
    record Cancel() implements UnitCommand {
    }

    /** Sets the running activity aside, to be resumed later with its timers and progress intact. */
    record Park() implements UnitCommand {
    }

    /** Drops the running activity without cancelling it, because it has finished or will never run again. */
    record Release() implements UnitCommand {
    }

    /** Cancels the parked activity. */
    record Discard() implements UnitCommand {
    }

    /** Runs the parked activity again. */
    record Resume() implements UnitCommand {
    }

    /** Starts an activity for the plan. */
    record Start(UnitPlan.Run run) implements UnitCommand {
    }

    /**
     * Carries out the operation, then reports {@link Event.Report.Performed}, or {@link Event.Report.Removed} if the
     * unit left the
     * world.
     */
    record Perform(UnitPlan.Operation operation) implements UnitCommand {
    }
}
