package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** Why a task is asked to decide; the reasons both units and ships know. */
@NullMarked
public sealed interface Reason permits UnitReason, ShipReason {
    Begin BEGIN = new Begin();
    Completed COMPLETED = new Completed();
    Performed PERFORMED = new Performed();

    /** The task became current: it was just given, or what suspended it has ended. */
    record Begin() implements UnitReason, ShipReason {
    }

    /** The activity the task started has finished. */
    record Completed() implements UnitReason, ShipReason {
    }

    /** The world operation the task asked for has been carried out. */
    record Performed() implements UnitReason, ShipReason {
    }
}
