package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The state machine of a task held inside another: decides for its own kind of reason, which its
 * parent narrows from the parent's, and returns the task's next state and plan, or null once the
 * task is done and its parent decides instead. Like {@link StateReducer}, it changes nothing.
 */
@NullMarked
interface UnitTaskReducer<T, R> {
    @Nullable
    Step<T, UnitPlan> decide(T task, R reason);
}
