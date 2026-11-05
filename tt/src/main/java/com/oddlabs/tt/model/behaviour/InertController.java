package com.oddlabs.tt.model.behaviour;

import org.jspecify.annotations.NullMarked;

/** The controller of a selectable that never acts: a land building or a ship's proxy. */
@NullMarked
public final class InertController implements Controller {
    private static final Task INERT = new Task.Inert();

    private final Behaviour behaviour = new NullBehaviour();

    @Override
    public void animate(float t) {
        behaviour.animate(t);
    }

    @Override
    public Behaviour getBehaviour() {
        return behaviour;
    }

    @Override
    public Task getPrimaryTask() {
        return INERT;
    }
}
