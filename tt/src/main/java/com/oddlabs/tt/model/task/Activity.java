package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/**
 * The imperative half of a task: animation, movement and timing. An activity reports how far it got
 * and, once done, what it found; what the selectable does next is decided elsewhere.
 */
@NullMarked
public interface Activity {
    enum State {
        COMMITTED,
        YIELDING,
        DONE
    }

    State animate(float t);

    boolean isBlocking();

    void cancel();
}
