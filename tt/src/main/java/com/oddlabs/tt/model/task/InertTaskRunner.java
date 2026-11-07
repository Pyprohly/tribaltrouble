package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** The task runner of a selectable that never acts: a land building or a ship's proxy. */
@NullMarked
public final class InertTaskRunner implements TaskRunner {
    private static final Task INERT = new Task.Inert();

    private final Activity activity = new NullActivity();

    @Override
    public void animate(float t) {
        activity.animate(t);
    }

    @Override
    public Activity getRunningActivity() {
        return activity;
    }

    @Override
    public Task getPrimaryTask() {
        return INERT;
    }
}
