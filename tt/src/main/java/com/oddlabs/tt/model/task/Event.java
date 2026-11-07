package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipAllocation;
import com.oddlabs.tt.model.weapon.MagicFactory;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Everything a unit reacts to: what the world does to it, and what its controller reports back
 * about the activities and operations it carried out.
 */
@NullMarked
public sealed interface Event {
    /** The unit is far enough constructed to act. */
    record Started() implements Event {
    }

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

    /** The running activity animated a frame and is not done. */
    record Progressed(Activity.State progress) implements Event {
        public static final Progressed INTERRUPTIBLE = new Progressed(Activity.State.INTERRUPTIBLE);
        public static final Progressed UNINTERRUPTIBLE = new Progressed(Activity.State.UNINTERRUPTIBLE);
    }

    /** The running activity is done, and reports what it found. */
    record Finished(Reason outcome) implements Event {
    }

    /** The operation the controller was asked for has been carried out. */
    record Performed() implements Event {
    }

    /** The operation took the unit out of the world, as entering a building does. */
    record Removed() implements Event {
    }
}
