package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A unit's tasks: what it does by default, the order it was given, and the
 * interruptions that have taken over from both. Dying runs ahead of a stun, which runs ahead of a
 * spell; the first one that is there runs, else the order, else the posture.
 */
@NullMarked
public record TaskSlots(Posture posture, @Nullable Order order, Interruption.@Nullable Casting casting,
                        Interruption.@Nullable Stunned stun, Interruption.@Nullable Dying dying) {
    public TaskSlots(Posture posture) {
        this(posture, null, null, null, null);
    }

    public @Nullable Interruption interruption() {
        if (dying != null) {
            return dying;
        }
        if (stun != null) {
            return stun;
        }
        if (casting != null) {
            return casting;
        }
        return null;
    }

    /** The task whose activity runs: its interruption, else its order, else its posture. */
    public Task current() {
        var interruption = interruption();
        if (interruption != null) {
            return interruption;
        }
        if (order != null) {
            return order;
        }
        return posture;
    }

    /** What the unit is doing, as other systems see it: its order, else its interruption, else its posture. */
    public Task primary() {
        if (order != null) {
            return order;
        }
        var interruption = interruption();
        if (interruption != null) {
            return interruption;
        }
        return posture;
    }

    public TaskSlots withOrder(@Nullable Order next) {
        return new TaskSlots(posture, next, casting, stun, dying);
    }

    /** Stuns the unit, replacing any stun already there. */
    public TaskSlots withStun(Interruption.Stunned next) {
        return new TaskSlots(posture, order, casting, next, dying);
    }

    /** Has the unit cast a spell, replacing any spell not yet cast. */
    public TaskSlots withCasting(Interruption.Casting next) {
        return new TaskSlots(posture, order, next, stun, dying);
    }

    /** Ends everything; nothing follows. */
    public TaskSlots dead() {
        return new TaskSlots(posture, null, null, null, new Interruption.Dying());
    }

    /** Ends the running interruption. */
    public TaskSlots withoutInterruption() {
        assert dying == null : "dying never ends";
        return stun != null ? new TaskSlots(posture, order, casting, null, null) : new TaskSlots(posture, order, null,
                null, null);
    }
}
