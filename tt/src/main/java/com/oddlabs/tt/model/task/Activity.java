package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NonNull;

/**
 * The imperative half of a task: animation, movement and timing. An activity reports how far it got
 * and, once done, what it found; what the selectable does next is decided elsewhere.
 */
public interface Activity {
    enum State {
        UNINTERRUPTIBLE,
        INTERRUPTIBLE,
        DONE
    }

    @NonNull
    State animate(float t);

    boolean isBlocking();

    void cancel();
}
