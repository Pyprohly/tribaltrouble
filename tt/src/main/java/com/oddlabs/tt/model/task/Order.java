package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A job a unit was given, by a player, an AI, a building or its own watch. A unit holds at most one;
 * giving another replaces it. The attempt counters bound how often a task retries after a walk that
 * did not get it where it needed to be, and start over whenever the task becomes current again.
 */
@NullMarked
public sealed interface Order extends Task {
    /** The same order with its retry budget restored, including that of any task nested in it. */
    Order resetAttempts();

    /** Walks to a target; an aggressive walk breaks off to hunt the enemies it sees. */
    record Walk(Target target, boolean aggressive, int attempts, @Nullable Hunt engaged) implements Order {
        public Walk(Target target, boolean aggressive) {
            this(target, aggressive, 0, null);
        }

        @Override
        public Walk resetAttempts() {
            return new Walk(target, aggressive, 0, engaged != null ? engaged.resetAttempts() : null);
        }
    }

    /** Walks to a target to defend it, hunting the enemies it sees on the way. */
    record Defend(Target target, int attempts, @Nullable Hunt engaged) implements Order {
        public Defend(Target target) {
            this(target, 0, null);
        }

        @Override
        public Defend resetAttempts() {
            return new Defend(target, 0, engaged != null ? engaged.resetAttempts() : null);
        }
    }

    /** Chases a target until it is dead or out of reach, attacking whenever it is in range. */
    record Hunt(Selectable<?> target, int attempts) implements Order {
        public Hunt(Selectable<?> target) {
            this(target, 0);
        }

        @Override
        public Hunt resetAttempts() {
            return new Hunt(target, 0);
        }
    }

    /** Attacks a target from where the unit stands, as from a tower or a ship. */
    record Attack(Selectable<?> target, @Nullable Seat seat) implements Order {
        public Attack(Selectable<?> target) {
            this(target, null);
        }

        @Override
        public Attack resetAttempts() {
            return this;
        }
    }

    /**
     * Harvests a supply type and carries it to a store, to the assigned building when there is one
     * and otherwise to the one the last search found.
     */
    record Gather(Class<? extends Supply> supplyType, @Nullable Supply supply, @Nullable Building assignedBuilding,
                  @Nullable Building foundBuilding, Phase phase) implements Order {
        public Gather(Class<? extends Supply> supplyType, @Nullable Supply supply,
                @Nullable Building assignedBuilding) {
            this(supplyType, supply, assignedBuilding, null, new Approaching(0));
        }

        @Override
        public Gather resetAttempts() {
            return new Gather(supplyType, supply, assignedBuilding, foundBuilding, phase.resetAttempts());
        }

        @Override
        public String key(Selectable<?> selectable) {
            return getClass().getName() + supplyType;
        }

        /** Where a gather is in its round trip; the attempts count walks made in the current phase. */
        public sealed interface Phase {
            Phase resetAttempts();
        }

        /** Walks to the supply, or decides which one to look for. */
        public record Approaching(int attempts) implements Phase {
            @Override
            public Approaching resetAttempts() {
                return new Approaching(0);
            }
        }

        /** Harvests until full; the attempts are those of the approach, kept for when the harvest ends. */
        public record Harvesting(Harvest harvest, int attempts) implements Phase {
            @Override
            public Harvesting resetAttempts() {
                return new Harvesting(harvest.resetAttempts(), 0);
            }
        }

        /** Carries the supplies to a store. */
        public record Delivering(int attempts) implements Phase {
            @Override
            public Delivering resetAttempts() {
                return new Delivering(0);
            }
        }

        /** Hands the supplies to the store it reached. */
        public record Depositing(Building store, int attempts) implements Phase {
            @Override
            public Depositing resetAttempts() {
                return new Depositing(store, 0);
            }
        }
    }

    /** Repairs or finishes a building, fetching wood whenever the unit carries none. */
    record Repair(Building building, Phase phase) implements Order {
        public Repair(Building building) {
            this(building, new Mending(0));
        }

        @Override
        public Repair resetAttempts() {
            return new Repair(building, phase.resetAttempts());
        }

        @Override
        public String key(Selectable<?> selectable) {
            return getClass().getName() + "@" + building.getGridX() + "," + building.getGridY();
        }

        /** Whether a repair works on the building or fetches wood for it; the attempts count walks made doing so. */
        public sealed interface Phase {
            Phase resetAttempts();
        }

        /** Walks to the building and repairs it. */
        public record Mending(int attempts) implements Phase {
            @Override
            public Mending resetAttempts() {
                return new Mending(0);
            }
        }

        /** Harvests wood to repair with. */
        public record Fetching(Harvest harvest, int attempts) implements Phase {
            @Override
            public Fetching resetAttempts() {
                return new Fetching(harvest.resetAttempts(), 0);
            }
        }
    }

    /** Walks into a building through its entrance, handing over what the unit carries. */
    record Enter(Building building, int attempts) implements Order {
        public Enter(Building building) {
            this(building.getEntrance(), 0);
        }

        @Override
        public Enter resetAttempts() {
            return new Enter(building, 0);
        }
    }

    /** Places a building site and then builds it. */
    record Build(Building building, int attempts) implements Order {
        public Build(Building building) {
            this(building, 0);
        }

        @Override
        public Build resetAttempts() {
            return new Build(building, 0);
        }
    }

    /** Finds the nearest store and moves into it. */
    record Transfer(@Nullable Building found, int attempts) implements Order {
        public Transfer() {
            this(null, 0);
        }

        @Override
        public Transfer resetAttempts() {
            return new Transfer(found, 0);
        }
    }

    /**
     * Harvests until the unit is full, searching for a supply of the type when it has none. Only
     * ever runs inside a {@link Gather} or a {@link Repair}.
     */
    record Harvest(@Nullable Supply supply, Class<? extends Supply> supplyType, int attempts) {
        public Harvest resetAttempts() {
            return new Harvest(supply, supplyType, 0);
        }
    }
}
