package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Supply;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Why a {@link Order.Harvest} decides. Only the end of a search it started changes what it does;
 * for any other reason it looks again at where it stands.
 */
@NullMarked
sealed interface HarvestReason {
    HarvestReason RESUMED = new Resumed();

    /** The harvest began, or the activity it started has finished. */
    record Resumed() implements HarvestReason {
    }

    /** The harvest's search for a supply ended; the supply is what it found, if anything. */
    record Found(@Nullable Supply supply) implements HarvestReason {
    }

    /** Narrows the reason the gather or repair holding the harvest decides for. */
    static HarvestReason of(UnitReason reason) {
        return reason instanceof UnitReason.FoundSupply found ? new Found(found.supply()) : RESUMED;
    }
}
