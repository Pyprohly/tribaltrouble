package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Supply;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A harvest's state machine: harvests until the unit is full, searching for a supply of the type
 * when it has none or the one it had is used up. It runs inside a {@link Order.Gather} or a
 * {@link Order.Repair}, which decides again once the harvest is done.
 */
@NullMarked
final class HarvestTaskReducer implements UnitTaskReducer<Order.Harvest, HarvestReason> {
    private final UnitView view;

    HarvestTaskReducer(UnitView view) {
        this.view = view;
    }

    @Override
    public @Nullable Step<Order.Harvest, UnitPlan> decide(Order.Harvest harvest, HarvestReason reason) {
        if (view.isFull(harvest.supplyType())) {
            return null;
        }
        Supply supply = harvest.supply();
        if (reason instanceof HarvestReason.Found found) {
            supply = found.supply();
        }
        if (supply != null && !supply.isEmpty() && view.isCloseEnough(0f, supply)) {
            return new Step<>(new Order.Harvest(supply, harvest.supplyType(), 0), new UnitPlan.Harvest(supply));
        }
        if (harvest.attempts() >= OrderTaskReducer.MAX_ATTEMPTS) {
            return null;
        }
        return new Step<>(new Order.Harvest(supply, harvest.supplyType(), harvest.attempts() + 1),
                new UnitPlan.FindSupply(harvest.supplyType()));
    }
}
