package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Holds a state and reduces events into it one at a time, handing each effect to the executor.
 * Events raised while an effect is carried out, or while {@link #report} runs its action, wait in a
 * queue for their turn, so a reduction never starts inside another.
 */
@NullMarked
final class ReducerLoop<S, E, F> {
    private final StateReducer<S, E, F> reducer;
    private final Consumer<F> executor;
    private final @Nullable TransitionTrace<S, E, F> trace;
    private S state;

    private final ArrayDeque<E> queue = new ArrayDeque<>();
    private boolean busy;

    ReducerLoop(StateReducer<S, E, F> reducer, S initial, Consumer<F> executor) {
        this(reducer, initial, executor, null);
    }

    ReducerLoop(StateReducer<S, E, F> reducer, S initial, Consumer<F> executor,
            @Nullable TransitionTrace<S, E, F> trace) {
        this.reducer = reducer;
        this.state = initial;
        this.executor = executor;
        this.trace = trace;
    }

    S state() {
        return state;
    }

    void dispatch(E event) {
        queue.addLast(event);
        drain();
    }

    /**
     * Runs an effect that reports its own result, such as an activity animating a frame, with events
     * held back. The result, if any, is reduced before the events the effect caused, because it
     * happened first; so a report always describes the state the reducer last saw.
     */
    void report(Supplier<? extends @Nullable E> action) {
        assert !busy : "an effect reported from inside a reduction";
        busy = true;
        E event;
        try {
            event = action.get();
        } finally {
            busy = false;
        }
        if (event != null) {
            queue.addFirst(event);
        }
        drain();
    }

    private void drain() {
        if (busy) {
            return;
        }
        busy = true;
        try {
            E event;
            while ((event = queue.pollFirst()) != null) {
                Transition<S, F> transition = reducer.reduce(state, event);

                // A reducer hands back the same state when nothing changed, as on most frame reports.
                if (trace != null && transition.nextState() != state) {
                    trace.record(event, transition.nextState(), transition.effect());
                }

                state = transition.nextState();
                executor.accept(transition.effect());
            }
        } finally {
            busy = false;
        }
    }
}
