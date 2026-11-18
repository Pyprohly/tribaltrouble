package com.oddlabs.tt.model.task;

import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;

/**
 * Everything a ship reacts to: what the world does to it, and what its controller reports back
 * about the activities and operations it carried out.
 */
@NullMarked
public sealed interface ShipEvent {
    /** The ship is far enough constructed to act. */
    record Started() implements ShipEvent {
    }

    /** The ship was sent to a new destination in place of the one it had. */
    record Sailed(Target target) implements ShipEvent {
    }

    /** The ship was destroyed, and gives up its voyage. */
    record Destroyed() implements ShipEvent {
    }

    /** What the controller reports back about the activities and operations it carried out. */
    sealed interface Report extends ShipEvent {
        /** The running activity is done, and reports what it found. */
        record Finished(ShipReason outcome) implements Report {
        }

        /** The operation the controller was asked for has been carried out. */
        record Performed(ShipPlan.Operation operation) implements Report {
        }
    }
}
