package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.pathfinder.Occupant;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Why a task is asked to decide. */
@NullMarked
public sealed interface Reason {
    Reason BEGIN = new Begin();
    Reason COMPLETED = new Completed();
    Reason PERFORMED = new Performed();
    Reason BLOCKED = new Blocked();
    Reason NO_ROUTE = new NoRoute();

    /** The task became current: it was just given, or what suspended it has ended. */
    record Begin() implements Reason {
    }

    /** The activity the task started has finished. */
    record Completed() implements Reason {
    }

    /** The world operation the task asked for has been carried out. */
    record Performed() implements Reason {
    }

    /** A finder walk ended; the occupant is what it found, if anything. */
    record Found(@Nullable Occupant occupant) implements Reason {
    }

    /** A watch or an aggressive walk saw an enemy. */
    record Spotted(Selectable<?> target) implements Reason {
    }

    /** A ship could not advance. */
    record Blocked() implements Reason {
    }

    /** A ship had no route to follow. */
    record NoRoute() implements Reason {
    }
}
