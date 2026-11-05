package com.oddlabs.tt.model.behaviour;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Runs a selectable's tasks: decides what to do, runs the behaviour for it, and decides again. */
@NullMarked
public interface Controller {
    void animate(float t);

    /** The running behaviour, or null when there is none, as for a unit that has just been removed. */
    @Nullable
    Behaviour getBehaviour();

    Task getPrimaryTask();
}
