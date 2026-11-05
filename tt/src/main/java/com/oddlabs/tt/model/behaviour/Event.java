package com.oddlabs.tt.model.behaviour;

import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.pathfinder.Occupant;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Why a task is asked to decide. */
@NullMarked
public sealed interface Event {
    Event BEGIN = new Begin();
    Event COMPLETED = new Completed();
    Event PERFORMED = new Performed();
    Event BLOCKED = new Blocked();
    Event NO_ROUTE = new NoRoute();

    /** The task became current: it was just given, or what suspended it has ended. */
    record Begin() implements Event {
    }

    /** The behaviour the task started has finished. */
    record Completed() implements Event {
    }

    /** The world operation the task asked for has been carried out. */
    record Performed() implements Event {
    }

    /** A finder walk ended; the occupant is what it found, if anything. */
    record Found(@Nullable Occupant occupant) implements Event {
    }

    /** A watch or an aggressive walk saw an enemy. */
    record Spotted(Selectable<?> target) implements Event {
    }

    /** A ship could not advance. */
    record Blocked() implements Event {
    }

    /** A ship had no route to follow. */
    record NoRoute() implements Event {
    }
}
