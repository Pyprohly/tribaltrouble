package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/**
 * A pure state machine: applies an event to a state and returns the next state and the effect the
 * controller carries out.
 */
@NullMarked
public interface StateReducer<S, E, F> {
    Transition<S, F> reduce(S state, E event);
}
