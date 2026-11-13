package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Why a unit's task is asked to decide. */
@NullMarked
public sealed interface UnitReason {
    Begin BEGIN = new Begin();
    Completed COMPLETED = new Completed();
    Performed PERFORMED = new Performed();

    /** The task became current: it was just given, or what suspended it has ended. */
    record Begin() implements UnitReason {
    }

    /** The activity the task started has finished. */
    record Completed() implements UnitReason {
    }

    /** The world operation the task asked for has been carried out. */
    record Performed() implements UnitReason {
    }

    /** A unit's walk looking for a supply ended; the supply is what it found, if anything. */
    record FoundSupply(@Nullable Supply supply) implements UnitReason {
    }

    /** A unit's walk looking for a store ended; the store is what it found, if anything. */
    record FoundStore(@Nullable Building store) implements UnitReason {
    }

    /** A unit's watch or aggressive walk saw an enemy. */
    record Spotted(Selectable<?> target) implements UnitReason {
    }
}
