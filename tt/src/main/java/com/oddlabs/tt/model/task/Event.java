package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipAllocation;
import com.oddlabs.tt.model.weapon.MagicFactory;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Something from outside a unit's tasks that they react to. */
@NullMarked
public sealed interface Event {
    /** The unit was given an order in place of the one it had, or had its order taken away when null. */
    record Ordered(@Nullable Order order) implements Event {
    }

    record Stunned(float seconds) implements Event {
    }

    /** The unit casts a spell; a stunned unit casts it once the stun wears off. */
    record Cast(MagicFactory factory, boolean clearOrder) implements Event {
    }

    record Died() implements Event {
    }

    record MountedTower() implements Event {
    }

    record MountedShip(Ship ship, ShipAllocation allocation) implements Event {
    }

    record Unmounted() implements Event {
    }

    /** The running activity finished, and the current task decides for the reason it gave. */
    record Finished(Reason reason) implements Event {
    }
}
