package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipAllocation;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.weapon.MagicFactory;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What a unit's decision asks the controller to do next: run an activity, or change the world once
 * and decide again. These are descriptions; the controller turns them into activities and calls.
 */
@NullMarked
public sealed interface UnitPlan {
    /** An activity to run until it finishes or the unit's task changes. */
    sealed interface Run extends UnitPlan {
    }

    /** A change to the world, after which the unit decides again with {@link Reason#PERFORMED}. */
    sealed interface Operation extends UnitPlan {
    }

    /** Stands idle, scanning for enemies in unit range, or in tower range when it cannot move. */
    record Watch(boolean canMove) implements Run {
    }

    /** Boards a fighting place on a ship, then scans for enemies in reach. */
    record Fight(Ship ship, ShipAllocation allocation) implements Run {
    }

    /** Boards a ship place and rows, steers or sits. */
    record Sit(Ship ship, ShipAllocation allocation) implements Run {
    }

    /** Walks to within range of a target. */
    record Walk(Target target, float range, boolean aggressive) implements Run {
    }

    /** Walks to the nearest supply of a type, reporting what it found. */
    record FindSupply(Class<? extends Supply> supplyType) implements Run {
    }

    /** Walks to the nearest building that stores supplies, reporting what it found. */
    record FindStore() implements Run {
    }

    record Attack(Selectable<?> target, @Nullable Ship ship, @Nullable ShipAllocation allocation) implements Run {
    }

    record Harvest(Supply supply) implements Run {
    }

    record Repair(Building building) implements Run {
    }

    record Cast(MagicFactory factory) implements Run {
    }

    record Stun(float seconds) implements Run {
    }

    record Die() implements Run {
    }

    /** Hands the unit's supplies to an adjacent store. */
    record Deposit(Building building) implements Operation {
    }

    /** Moves the unit into an adjacent building, handing over its supplies and weapon when asked. */
    record Enter(Building building, boolean withCargo) implements Operation {
    }

    /** Places a building site the unit is standing at. */
    record Place(Building building) implements Operation {
    }
}
