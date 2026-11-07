package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NonNull;

/** An activity a unit runs for one of its tasks. */
public interface UnitActivity extends Activity {
    /**
     * What the activity has to report, read once after {@link #animate} returned DONE. A posture's
     * activity is set aside and resumed rather than replaced, so it can be done many times and must be
     * ready to report afresh each time.
     */
    default @NonNull Reason outcome() {
        return Reason.COMPLETED;
    }
}
