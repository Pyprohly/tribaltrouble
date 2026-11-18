package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** What a ship's transition asks its controller to do, in the order given. */
@NullMarked
public sealed interface ShipCommand {
    Stop INTERRUPT = new Interrupt();
    Stop RELEASE = new Release();

    /** Ends the running activity before the next plan starts. */
    sealed interface Stop extends ShipCommand {
    }

    /** Cancels the running activity. */
    record Interrupt() implements Stop {
    }

    /** Releases the running activity without cancelling it, because it has finished. */
    record Release() implements Stop {
    }

    /** Starts an activity for the plan. */
    record Run(ShipPlan.Run plan) implements ShipCommand {
    }

    /** Carries out the operation, then reports {@link ShipEvent.Report.Performed}. */
    record Perform(ShipPlan.Operation operation) implements ShipCommand {
    }
}
