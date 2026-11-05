package com.oddlabs.tt.model.behaviour;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipAllocation;
import org.jspecify.annotations.NullMarked;

/**
 * What a unit does when it has no order. Only mounting and unmounting change it, and its behaviour
 * is kept, not restarted, while an order or an interruption runs above it.
 */
@NullMarked
public sealed interface Posture extends Task {
    /** Watches for enemies; a unit that can move goes after them, a tower occupant shoots. */
    record Idle(boolean canMove) implements Posture {
        @Override
        public String key(Selectable<?> selectable) {
            return abilityKey(this, selectable);
        }
    }

    /** Crews a ship in a fighting place and shoots at enemies in reach. */
    record Fighting(Ship ship, ShipAllocation allocation) implements Posture {
        @Override
        public String key(Selectable<?> selectable) {
            return abilityKey(this, selectable);
        }
    }

    /** Crews a ship as a rower, steerer or passenger. */
    record Sitting(Ship ship, ShipAllocation allocation) implements Posture {
    }

    private static String abilityKey(Posture posture, Selectable<?> selectable) {
        Abilities abilities = selectable.getAbilities();
        return posture.getClass().getName() + abilities.hasAbilities(Abilities.BUILD) + abilities.hasAbilities(
                Abilities.MAGIC);
    }
}
