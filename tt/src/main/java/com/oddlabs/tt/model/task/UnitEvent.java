package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipAllocation;
import com.oddlabs.tt.model.weapon.MagicFactory;
import org.jspecify.annotations.NullMarked;

/**
 * Everything a unit reacts to: what the world does to it, and what its controller reports back
 * about the activities and operations it carried out.
 */
@NullMarked
public sealed interface UnitEvent {
    /** The unit is far enough constructed to act. */
    record Started() implements UnitEvent {
    }

    /** The unit was given an order in place of the one it had. */
    record Ordered(Order order) implements UnitEvent {
    }

    /**
     * The unit was excused from its order: it has none any more, but an activity running for it is
     * left to finish.
     */
    record Excused() implements UnitEvent {
    }

    record Died() implements UnitEvent {
    }

    record Stunned(float seconds) implements UnitEvent {
    }

    /** The unit casts a spell; a stunned unit casts it once the stun wears off. */
    record Cast(MagicFactory factory, boolean clearOrder) implements UnitEvent {
    }

    record MountedTower() implements UnitEvent {
    }

    record MountedShip(Ship ship, ShipAllocation allocation) implements UnitEvent {
    }

    record Unmounted() implements UnitEvent {
    }

    /** What the controller reports back about the activities and operations it carried out. */
    sealed interface Report extends UnitEvent {
        /** The running activity animated a frame, is not done, and started or stopped yielding. */
        record Progressed(Activity.State progress) implements Report {
        }

        /** The running activity is done, and reports what it found. */
        record Finished(UnitReason outcome) implements Report {
        }

        /** The operation has been carried out, and the unit is still in the world. */
        record Performed(UnitPlan.Operation operation) implements Report {
        }

        /** The operation took the unit out of the world, as entering a building does. */
        record Removed() implements Report {
        }
    }
}
