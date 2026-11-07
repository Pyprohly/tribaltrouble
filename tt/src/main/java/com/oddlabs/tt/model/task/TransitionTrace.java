package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * The last transitions a state machine made, kept so a script can ask why a selectable did what it
 * did. Only recorded when the {@value #PROPERTY} system property is true; it never feeds back into
 * the simulation.
 */
@NullMarked
public final class TransitionTrace<S, E, F> {
    public static final String PROPERTY = "com.oddlabs.tt.model.task.trace";
    public static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private static final int CAPACITY = 16;

    /** An event, the state it left, and the effect the controller carried out for it. */
    public record Entry<S, E, F>(E event, S state, F effect) {
    }

    private final ArrayDeque<Entry<S, E, F>> entries = new ArrayDeque<>(CAPACITY);
    private final BiPredicate<? super E, ? super F> worthKeeping;

    public TransitionTrace() {
        this((event, effect) -> true);
    }

    /** Keeps only the transitions the predicate accepts, so routine ones do not push out the rest. */
    public TransitionTrace(BiPredicate<? super E, ? super F> worthKeeping) {
        this.worthKeeping = worthKeeping;
    }

    void record(E event, S state, F effect) {
        if (!worthKeeping.test(event, effect)) {
            return;
        }
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(new Entry<>(event, state, effect));
    }

    /** The recorded transitions, oldest first. */
    public List<Entry<S, E, F>> entries() {
        return List.copyOf(entries);
    }
}
