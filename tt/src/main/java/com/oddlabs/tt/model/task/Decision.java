package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** The task state a decision leaves, and the plan the reducer turns into commands. */
@NullMarked
record Decision<S, P>(S state, P plan) {
}
