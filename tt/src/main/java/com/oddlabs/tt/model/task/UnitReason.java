package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Why a unit's task is asked to decide. */
@NullMarked
public sealed interface UnitReason extends Reason permits Reason.Begin, Reason.Completed, Reason.Performed,
        UnitReason.FoundSupply, UnitReason.FoundStore, UnitReason.Spotted {
    /** A walk looking for a supply ended; the supply is what it found, if anything. */
    record FoundSupply(@Nullable Supply supply) implements UnitReason {
    }

    /** A walk looking for a store ended; the store is what it found, if anything. */
    record FoundStore(@Nullable Building store) implements UnitReason {
    }

    /** A watch or an aggressive walk saw an enemy. */
    record Spotted(Selectable<?> target) implements UnitReason {
    }
}
