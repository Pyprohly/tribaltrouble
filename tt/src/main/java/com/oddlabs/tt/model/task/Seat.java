package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipAllocation;
import org.jspecify.annotations.NullMarked;

/** The place a unit crews on a ship. */
@NullMarked
public record Seat(Ship ship, ShipAllocation allocation) {
}
