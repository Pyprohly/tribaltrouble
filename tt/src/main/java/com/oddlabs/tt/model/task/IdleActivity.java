package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.AttackScanFilter;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class IdleActivity implements UnitActivity {
    private static final float MIN_SCAN_DELAY = 1f;
    private static final float MAX_SCAN_DELAY = 2f;

    private final @NonNull Unit unit;
    private final @NonNull AttackScanFilter scanFilter;
    private float scanDelay;
    private @Nullable Selectable<?> spotted;

    public IdleActivity(@NonNull Unit unit, boolean canMove) {
        this.unit = unit;
        this.scanFilter = new AttackScanFilter(unit.getOwner(),
                canMove ? AttackScanFilter.UNIT_RANGE : AttackScanFilter.TOWER_RANGE);
    }

    @Override
    public @NonNull State animate(float t) {
        unit.switchToIdleAnimation();
        scanDelay -= t;
        if (scanDelay > 0)
            return State.YIELDING;
        scanDelay = MIN_SCAN_DELAY + unit.getOwner().getWorld().getRandom().nextFloat() * (MAX_SCAN_DELAY - MIN_SCAN_DELAY);
        if (unit.getAbilities().hasAbilities(Abilities.ATTACK))
            unit.scanVicinity(scanFilter);
        spotted = scanFilter.removeTarget();
        return spotted != null ? State.DONE : State.YIELDING;
    }

    @Override
    public @NonNull UnitReason outcome() {
        Selectable<?> target = spotted;
        spotted = null;
        return target != null ? new UnitReason.Spotted(target) : UnitReason.COMPLETED;
    }

    @Override
    public boolean isBlocking() {
        return true;
    }

    @Override
    public void cancel() {
    }
}
