package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** What a task decided: its next state, and the plan the controller carries out for it. */
@NullMarked
record Step<T, P>(T next, P plan) {
}
