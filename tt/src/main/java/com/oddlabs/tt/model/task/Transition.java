package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** The state a transition leaves, and the effect the controller carries out for it. */
@NullMarked
public record Transition<S, F>(S nextState, F effect) {
}
