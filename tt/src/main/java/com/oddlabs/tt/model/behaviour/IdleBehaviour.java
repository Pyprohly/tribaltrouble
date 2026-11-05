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
    private final @NonNull AttackScanFilter scan_filter;
    private float scan_delay;
    private @Nullable Selectable<?> spotted;

    public IdleBehaviour(@NonNull Unit unit, boolean can_move) {
        this.unit = unit;
        this.scan_filter = new AttackScanFilter(unit.getOwner(),
                can_move ? AttackScanFilter.UNIT_RANGE : AttackScanFilter.TOWER_RANGE);
    }

    @Override
    public @NonNull State animate(float t) {
        unit.switchToIdleAnimation();
        scan_delay -= t;
        if (scan_delay > 0)
            return State.INTERRUPTIBLE;
        scan_delay = MIN_SCAN_DELAY + unit.getOwner().getWorld().getRandom().nextFloat() * (MAX_SCAN_DELAY - MIN_SCAN_DELAY);
        if (unit.getAbilities().hasAbilities(Abilities.ATTACK))
            unit.scanVicinity(scan_filter);
        spotted = scan_filter.removeTarget();
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
