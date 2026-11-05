package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;

public final class StunActivity implements Activity {
    private final @NonNull Unit unit;
    private float time;

    public StunActivity(@NonNull Unit unit, float time) {
        this.unit = unit;
        this.time = time;
    }

    @Override
    public @NonNull State animate(float t) {
        unit.switchToIdleAnimation();
        time -= t;
        return time > 0 ? State.UNINTERRUPTIBLE : State.DONE;
    }

    @Override
    public @NonNull State initialState() {
        return State.UNINTERRUPTIBLE;
    }

    @Override
    public boolean isBlocking() {
        return true;
    }

    @Override
    public void cancel() {
    }
}
