package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NonNull;

/** An activity a ship runs for a leg of its voyage. */
public interface ShipActivity extends Activity {
    /** What the activity has to report, read once after {@link #animate} returned DONE. */
    default @NonNull ShipReason outcome() {
        return Reason.COMPLETED;
    }
}
