package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A unit's tasks: what it does by default, the order it was given, and the
 * interruptions that have taken over from both. Interruptions run first to last; the first one
 * that is there runs, else the order, else the posture.
 */
@NullMarked
public record TaskStack(Posture posture, @Nullable Order order, List<Interruption> interruptions) {
    public TaskStack {
        interruptions = List.copyOf(interruptions);
    }

    public TaskStack(Posture posture) {
        this(posture, null, List.of());
    }

    public @Nullable Interruption interruption() {
        return interruptions.isEmpty() ? null : interruptions.getFirst();
    }

    /** The task whose activity runs. */
    public Task current() {
        Interruption interruption = interruption();
        if (interruption != null) {
            return interruption;
        }
        return order != null ? order : posture;
    }

    /** What the unit is doing, as other systems see it: its order, else its interruption, else its posture. */
    public Task primary() {
        if (order != null) {
            return order;
        }
        Interruption interruption = interruption();
        return interruption != null ? interruption : posture;
    }

    public TaskStack withOrder(@Nullable Order next) {
        return new TaskStack(posture, next, interruptions);
    }

    /** Puts an interruption ahead of the ones already there. */
    public TaskStack interruptedBy(Interruption interruption) {
        List<Interruption> next = new ArrayList<>(interruptions.size() + 1);
        next.add(interruption);
        next.addAll(interruptions);
        return new TaskStack(posture, order, next);
    }

    /** Replaces the running interruption. */
    public TaskStack replacingInterruption(Interruption interruption) {
        return withoutInterruption().interruptedBy(interruption);
    }

    /** Queues an interruption to run once the ones already there have ended. */
    public TaskStack followedBy(Interruption interruption) {
        List<Interruption> next = new ArrayList<>(interruptions);
        next.add(interruption);
        return new TaskStack(posture, order, next);
    }

    /** Ends the running interruption. */
    public TaskStack withoutInterruption() {
        return new TaskStack(posture, order, interruptions.subList(1, interruptions.size()));
    }
}
