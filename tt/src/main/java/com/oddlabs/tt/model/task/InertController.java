package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** The controller of a selectable that never acts: a land building or a ship's proxy. */
@NullMarked
public final class InertController implements Controller {
    private static final Task INERT = new Task.Inert();

    private final Activity activity = new NullActivity();

    @Override
    public void animate(float t) {
        activity.animate(t);
    }

    @Override
    public Activity getActivity() {
        return activity;
    }

    @Override
    public Task getPrimaryTask() {
        return INERT;
    }
}
