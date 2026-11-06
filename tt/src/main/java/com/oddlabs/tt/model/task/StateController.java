package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/**
 * Holds a selectable's state and takes the events its tasks react to. Java has no declaration-site
 * variance, so a caller that wants {@code S} covariant or {@code E} contravariant writes
 * {@code StateController<? extends S, ? super E>}.
 */
@NullMarked
public interface StateController<S, E> {
    S getState();

    void dispatch(E event);
}
