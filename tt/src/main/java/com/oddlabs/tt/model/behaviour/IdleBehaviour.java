package com.oddlabs.tt.model.behaviour;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.AttackScanFilter;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class IdleBehaviour implements Behaviour {
    private static final float MIN_SCAN_DELAY = 1f;
    private static final float MAX_SCAN_DELAY = 2f;

    private final @NonNull Unit unit;
    private final @NonNull AttackScanFilter scanFilter;
    private float scanDelay;
    private @Nullable Selectable<?> spotted;

    public IdleBehaviour(@NonNull Unit unit, boolean canMove) {
        this.unit = unit;
        this.scanFilter = new AttackScanFilter(unit.getOwner(),
                canMove ? AttackScanFilter.UNIT_RANGE : AttackScanFilter.TOWER_RANGE);
    }

    @Override
    public @NonNull State animate(float t) {
        unit.switchToIdleAnimation();
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

    @Override
    public boolean isBlocking() {
        return true;
    }

    @Override
    public void forceInterrupted() {
    }
}
