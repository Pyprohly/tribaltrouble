package com.oddlabs.tt.model.task;

import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;

/** Something from outside a ship's voyages that they react to. */
@NullMarked
public sealed interface ShipEvent {
    /** The ship was sent to a new destination in place of the one it had. */
    record Sailed(Target target) implements ShipEvent {
    }

    record Docked() implements ShipEvent {
    }
}
