package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NonNull;

public final class NullActivity implements ShipActivity {
    @Override
    public @NonNull State animate(float t) {
        return State.INTERRUPTIBLE;
    }

    @Override
    public boolean isBlocking() {
        return true;
    }

    @Override
    public void cancel() {
    }
}
