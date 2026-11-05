package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.weapon.MagicFactory;
import org.jspecify.annotations.NullMarked;

/**
 * Something that takes over a unit whatever it was doing. The order beneath it is suspended, not
 * lost, and resumes when the interruption ends; only dying ends everything.
 */
@NullMarked
public sealed interface Interruption extends Task {
    /** Stands dazed. */
    record Stunned(float seconds) implements Interruption {
    }

    /** Casts a spell. */
    record Casting(MagicFactory factory) implements Interruption {
    }

    /** Dies. Nothing follows. */
    record Dying() implements Interruption {
    }
}
