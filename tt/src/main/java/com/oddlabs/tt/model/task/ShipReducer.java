package com.oddlabs.tt.model.task;

import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;

/** A ship's decisions: whether it is docked or on a voyage, and which leg of the voyage it is on. */
@NullMarked
public final class ShipReducer {
    /** How many times a ship fails to work itself loose before it gives up the voyage. */
    private static final int MAX_FAILED_UNSTUCKS = 3;

    public sealed interface ShipPlan {
    }

    /** An activity to run until it finishes or the ship gets a new task. */
    public sealed interface Run extends ShipPlan {
    }

    /** A change to the world, after which the ship decides again with {@link Reason#PERFORMED}. */
    public enum Operation implements ShipPlan {
        /** The ship reached deep water. */
        FINISH_SLIDE,
        /** The ship arrived, or found no route; it settles where it is. */
        END_TRIP
    }

    public record Moor() implements Run {
    }

    public record Slide() implements Run {
    }

    public record Sail(Target target) implements Run {
    }

    public record Unstuck() implements Run {
    }

    public record Decision(Task state, ShipPlan plan) {
    }

    private ShipReducer() {
    }

    /**
     * @param slid whether the ship has already left the shore it was built on
     */
    public static Decision decide(Task state, Reason reason, boolean slid) {
        if (!(state instanceof Voyage voyage)) {
            return new Decision(new Task.Inert(), new Moor());
        }
        if (reason instanceof Reason.Begin) {
            return leg(new Voyage(voyage.target(), slid ? Voyage.Phase.SAILING : Voyage.Phase.SLIDING,
                    voyage.failedUnstucks()));
        }
        return switch (voyage.phase()) {
            case SLIDING -> reason instanceof Reason.Performed ? leg(with(voyage, Voyage.Phase.SAILING,
                    voyage.failedUnstucks())) : new Decision(voyage, Operation.FINISH_SLIDE);
            case SAILING -> reason instanceof Reason.Blocked ? leg(with(voyage, Voyage.Phase.UNSTICKING,
                    voyage.failedUnstucks())) : new Decision(new Task.Inert(), Operation.END_TRIP);
            case UNSTICKING -> {
                if (reason instanceof Reason.Completed) {
                    yield leg(with(voyage, Voyage.Phase.SAILING, 0));
                }
                int failed = voyage.failedUnstucks() + 1;
                if (failed >= MAX_FAILED_UNSTUCKS) {
                    yield new Decision(new Task.Inert(), new Moor());
                }
                yield leg(with(voyage, Voyage.Phase.SAILING, failed));
            }
        };
    }

    private static Voyage with(Voyage voyage, Voyage.Phase phase, int failedUnstucks) {
        return new Voyage(voyage.target(), phase, failedUnstucks);
    }

    private static Decision leg(Voyage voyage) {
        return new Decision(voyage, switch (voyage.phase()) {
            case SLIDING -> new Slide();
            case SAILING -> new Sail(voyage.target());
            case UNSTICKING -> new Unstuck();
        });
    }
}
