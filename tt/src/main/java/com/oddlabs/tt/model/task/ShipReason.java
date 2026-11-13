package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** Why a ship's voyage is asked to decide. */
@NullMarked
public sealed interface ShipReason {
    Begin BEGIN = new Begin();
    Completed COMPLETED = new Completed();
    Performed PERFORMED = new Performed();
    Blocked BLOCKED = new Blocked();
    NoRoute NO_ROUTE = new NoRoute();

    /** The voyage was just given, or the ship started. */
    record Begin() implements ShipReason {
    }

    /** The activity the leg started has finished. */
    record Completed() implements ShipReason {
    }

    /** The world operation the leg asked for has been carried out. */
    record Performed() implements ShipReason {
    }

    /** The ship could not advance. */
    record Blocked() implements ShipReason {
    }

    /** The ship had no route to follow. */
    record NoRoute() implements ShipReason {
    }
}
