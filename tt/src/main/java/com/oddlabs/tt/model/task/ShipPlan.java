package com.oddlabs.tt.model.task;

import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;

/**
 * What a ship's decision asks the controller to do next: run an activity, or change the world once and decide again.
 */
@NullMarked
public sealed interface ShipPlan {
    /** An activity to run until it finishes or the ship gets a new task. */
    sealed interface Run extends ShipPlan {
    }

    /** A change to the world, after which the ship decides again with {@link Reason#PERFORMED}. */
    enum Operation implements ShipPlan {
        /** The ship reached deep water. */
        FINISH_SLIDE,
        /** The ship arrived, or found no route; it settles where it is. */
        END_TRIP
    }

    record Moor() implements Run {
    }

    record Slide() implements Run {
    }

    record Sail(Target target) implements Run {
    }

    record Unstuck() implements Run {
    }
}
