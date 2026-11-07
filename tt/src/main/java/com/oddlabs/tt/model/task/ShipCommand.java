package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** What a ship's transition asks its controller to do, in the order given. */
@NullMarked
public sealed interface ShipCommand {
    ShipCommand CANCEL = new Cancel();
    ShipCommand RELEASE = new Release();

    /** Cancels the running activity. */
    record Cancel() implements ShipCommand {
    }

    /** Drops the running activity without cancelling it, because it has finished. */
    record Release() implements ShipCommand {
    }

    /** Starts an activity for the plan. */
    record Start(ShipPlan.Run run) implements ShipCommand {
    }

    /** Carries out the operation, then reports {@link ShipEvent.Report.Performed}. */
    record Perform(ShipPlan.Operation operation) implements ShipCommand {
    }
}
