package com.oddlabs.tt.model.behaviour;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.AttackScanFilter;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipAllocation;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class ShipAttackBehaviour implements Behaviour {
    private static final float MIN_SCAN_DELAY = 0.1f;
    private static final float MAX_SCAN_DELAY = 0.2f;
    private static final int SCAN_RANGE = AttackScanFilter.TOWER_RANGE + 10;
    private final static float TOTAL_BOARDING_TIME = 1.0f;

    private final Unit unit;
    private final Ship ship;
    private final ShipAllocation allocation;
    private final AttackScanFilter scanFilter;
    private boolean boarded = false;
    private float boarding_time = 0.0f;
    private float scanDelay;
    private @Nullable Selectable<?> spotted;

    public ShipAttackBehaviour(Unit unit, Ship ship, ShipAllocation allocation) {
        this.unit = unit;
        this.ship = ship;
        this.allocation = allocation;
        this.scanFilter = new AttackScanFilter(unit.getOwner(), SCAN_RANGE);
    }

    @Override
    public @NonNull State animate(float t) {
        if (unit.isDead()) {
            return State.DONE;
        }
        if (ship.isMoving() || boarding_time >= TOTAL_BOARDING_TIME) {
            boarded = true;
        }
        if (!boarded) {
            boarding_time += t;
            unit.switchAnimation(3.0f, Unit.Animation.MOVING);
            allocation.updateIntermediate(unit, ship, boarding_time / TOTAL_BOARDING_TIME);
            return State.INTERRUPTIBLE;
        }
        unit.switchToIdleAnimation();
        allocation.updateFinal(unit, ship);
        scanDelay -= t;
        if (scanDelay > 0)
            return State.INTERRUPTIBLE;
        scanDelay = MIN_SCAN_DELAY + unit.getOwner().getWorld().getRandom().nextFloat() * (MAX_SCAN_DELAY - MIN_SCAN_DELAY);
        if (unit.getAbilities().hasAbilities(Abilities.ATTACK))
            unit.scanVicinity(scanFilter);
        spotted = scanFilter.removeTarget();
        return spotted != null ? State.DONE : State.INTERRUPTIBLE;
    }

    @Override
    public @NonNull Event outcome() {
        Selectable<?> target = spotted;
        spotted = null;
        return target != null ? new Event.Spotted(target) : Event.COMPLETED;
    }

    public final boolean isBlocking() {
        return true;
    }

    public final void forceInterrupted() {
    }
}
