package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** Why a ship's voyage is asked to decide. */
@NullMarked
public sealed interface ShipReason extends Reason permits Reason.Begin, Reason.Completed, Reason.Performed,
        ShipReason.Blocked, ShipReason.NoRoute {
    Blocked BLOCKED = new Blocked();
    NoRoute NO_ROUTE = new NoRoute();

    /** The ship could not advance. */
    record Blocked() implements ShipReason {
    }

    /** The ship had no route to follow. */
    record NoRoute() implements ShipReason {
    }
}
