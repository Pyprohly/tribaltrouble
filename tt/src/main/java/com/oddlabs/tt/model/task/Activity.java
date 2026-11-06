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

    /**
     * The state the activity is in before its first frame, so a decision taken right after it
     * starts sees the same boundary as one taken a frame later.
     */
    default @NonNull State initialState() {
        return State.INTERRUPTIBLE;
    }

    /** What the activity has to report, read once after {@link #animate} returned DONE. */
    default @NonNull Reason outcome() {
        return Reason.COMPLETED;
    }

    boolean isBlocking();

    void cancel();
}
