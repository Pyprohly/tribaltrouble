package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

/** What a ship's decisions may read about it. Nothing here changes anything. */
@NullMarked
@FunctionalInterface
public interface ShipView {
    /** Whether the ship has already left the shore it was built on. */
    boolean slid();
}
