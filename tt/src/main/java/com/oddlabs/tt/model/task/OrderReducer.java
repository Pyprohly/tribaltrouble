package com.oddlabs.tt.model.task;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The state machine of a unit's order: decides what the order does next, or returns null once it
 * is done and the unit's posture takes over. An order can become another order, which decides in
 * the same call; a gather or a repair holds a {@link Order.Harvest} and lets its
 * {@link HarvestReducer} decide while that harvest runs.
 */
@NullMarked
final class OrderReducer implements UnitTaskReducer<Order, UnitReason> {
    /** How many walks a task makes towards something before it gives up on reaching it. */
    static final int MAX_ATTEMPTS = 1;

    private final UnitView view;
    private final HarvestReducer harvestReducer;

    OrderReducer(UnitView view) {
        this.view = view;
        this.harvestReducer = new HarvestReducer(view);
    }

    @Override
    public @Nullable Step<Order, UnitPlan> decide(Order order, UnitReason reason) {
        if (reason instanceof UnitReason.Begin) {
            order = order.withAttemptsReset();
        }
        return switch (order) {
            case Order.March march -> march(march, reason);
            case Order.Hunt hunt -> hunt(hunt);
            case Order.Attack attack -> attack(attack);
            case Order.Gather gather -> gather(gather, reason);
            case Order.Repair repair -> repair(repair, reason);
            case Order.Enter enter -> enter(enter, reason);
            case Order.Build build -> build(build);
            case Order.Transfer transfer -> transfer(transfer, reason);
        };
    }

    private @Nullable Step<Order, UnitPlan> march(Order.March march, UnitReason reason) {
        if (reason instanceof UnitReason.Spotted spotted) {
            // Breaking off to hunt restores the walk's budget, so it carries on once the hunt
            // ends.
            return march(march.withProgress(0, new Order.Hunt(spotted.target())), UnitReason.BEGIN);
        }
        Order engaged = march.engaged();
        if (engaged != null && decide(engaged, reason) instanceof Step(Order next, UnitPlan plan)) {
            return new Step<>(march.withProgress(march.attempts(), next), plan);
        }
        if (march.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step<>(
                march.withProgress(march.attempts() + 1, null),
                new UnitPlan.Walk(march.target(), 0f, march.aggressive())
        );
    }

    private @Nullable Step<Order, UnitPlan> hunt(Order.Hunt hunt) {
        Selectable<?> target = hunt.target();
        if (target.isDead()) {
            return null;
        }
        float range = view.range(target);
        if (view.isCloseEnough(range, target)) {
            return new Step<>(new Order.Hunt(target, 0), new UnitPlan.Attack(target, null));
        }
        if (hunt.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step<>(new Order.Hunt(target, hunt.attempts() + 1), new UnitPlan.Walk(target, range, false));
    }

    private @Nullable Step<Order, UnitPlan> attack(Order.Attack attack) {
        if (attack.target().isDead() || !view.canReach(attack.target())) {
            return null;
        }
        return new Step<>(attack, new UnitPlan.Attack(attack.target(), attack.seat()));
    }

    private @Nullable Step<Order, UnitPlan> gather(Order.Gather gather, UnitReason reason) {
        int approachAttempts = 0;
        int deliverAttempts = 0;
        switch (gather.phase()) {
            case Order.Gather.Approaching approaching -> approachAttempts = approaching.attempts();
            case Order.Gather.Harvesting harvesting -> {
                if (harvestReducer.decide(
                        harvesting.harvest(),
                        HarvestReason.of(reason)
                ) instanceof Step(Order.Harvest harvest, UnitPlan plan)) {
                    return new Step<>(
                            gather.withPhase(new Order.Gather.Harvesting(harvest, harvesting.attempts())),
                            plan
                    );
                }
                approachAttempts = harvesting.attempts();
            }
            case Order.Gather.Delivering delivering -> {
                if (reason instanceof UnitReason.FoundStore found) {
                    gather = gather.withFoundBuilding(found.store());
                }
                deliverAttempts = delivering.attempts();
            }
            case Order.Gather.Depositing depositing -> {
                if (reason instanceof UnitReason.Performed && view.carriesAnything()) {
                    // The store is full: move into it rather than carry the rest around.
                    return enter(new Order.Enter(depositing.store()), UnitReason.BEGIN);
                }
                deliverAttempts = depositing.attempts();
            }
        }
        if (view.carries(gather.supplyType())) {
            return deliver(gather, deliverAttempts);
        }
        return approach(gather, approachAttempts);
    }

    private @Nullable Step<Order, UnitPlan> approach(Order.Gather gather, int attempts) {
        Supply supply = gather.supply();
        if (supply != null && supply.isDead()) {
            supply = null;
            attempts = 0;
        }
        if (supply != null && view.isCloseEnough(view.range(supply), supply)) {
            return gather(gather.withSupply(supply).withPhase(new Order.Gather.Harvesting(new Order.Harvest(supply,
                    gather.supplyType(), 0), attempts)), UnitReason.BEGIN);
        }
        if (attempts >= MAX_ATTEMPTS) {
            return transfer(new Order.Transfer(), UnitReason.BEGIN);
        }
        if (supply == null) {
            return gather(gather.withSupply(null).withPhase(new Order.Gather.Harvesting(new Order.Harvest(null,
                    gather.supplyType(), 0), attempts + 1)), UnitReason.BEGIN);
        }
        return new Step<>(gather.withSupply(supply).withPhase(new Order.Gather.Approaching(attempts + 1)),
                new UnitPlan.Walk(supply));
    }

    private @Nullable Step<Order, UnitPlan> deliver(Order.Gather gather, int attempts) {
        Building assigned = gather.assignedBuilding();
        boolean assignedStanding = assigned != null && !assigned.isDead();
        Building found = gather.foundBuilding();
        Building store = null;
        if (assignedStanding) {
            store = assigned.getEntrance();
        } else if (found != null) {
            store = found.getEntrance();
        }
        if (store != null && view.isCloseEnough(0f, store)) {
            return new Step<>(
                    gather.withPhase(new Order.Gather.Depositing(store, attempts)),
                    new UnitPlan.Deposit(store)
            );
        }
        if (attempts >= MAX_ATTEMPTS) {
            return null;
        }
        Order.Gather next = gather.withPhase(new Order.Gather.Delivering(attempts + 1));
        if (assignedStanding) {
            return new Step<>(next, new UnitPlan.Walk(assigned.getEntrance()));
        }
        return new Step<>(next, new UnitPlan.FindStore());
    }

    private @Nullable Step<Order, UnitPlan> repair(Order.Repair repair, UnitReason reason) {
        Building building = repair.building();
        int fetchAttempts = 0;
        int mendAttempts = 0;
        switch (repair.phase()) {
            case Order.Repair.Fetching fetching -> {
                if (harvestReducer.decide(
                        fetching.harvest(),
                        HarvestReason.of(reason)
                ) instanceof Step(Order.Harvest harvest, UnitPlan plan)) {
                    return new Step<>(
                            repair.withPhase(new Order.Repair.Fetching(harvest, fetching.attempts())),
                            plan
                    );
                }
                fetchAttempts = fetching.attempts();
            }
            case Order.Repair.Mending mending -> mendAttempts = mending.attempts();
        }
        if (building.isDead()) {
            return null;
        }
        if (view.carries(TreeSupply.class)) {
            if (view.isCloseEnough(0f, building)) {
                if (building.isDamaged()) {
                    return new Step<>(
                            repair.withPhase(new Order.Repair.Mending(mendAttempts)),
                            new UnitPlan.Repair(building));
                }
                if (building.getAbilities().hasAbilities(Abilities.SUPPLY_CONTAINER) && view.sharesOwner(building)) {
                    return enter(new Order.Enter(building), UnitReason.BEGIN);
                }
                return null;
            }
            if (mendAttempts >= MAX_ATTEMPTS) {
                return null;
            }
            return new Step<>(
                    repair.withPhase(new Order.Repair.Mending(mendAttempts + 1)),
                    new UnitPlan.Walk(building));
        }
        if (fetchAttempts >= MAX_ATTEMPTS) {
            return null;
        }
        return repair(
                repair.withPhase(
                        new Order.Repair.Fetching(
                                new Order.Harvest(null, TreeSupply.class, 0),
                                fetchAttempts + 1
                        )
                ),
                UnitReason.BEGIN
        );
    }

    private @Nullable Step<Order, UnitPlan> enter(Order.Enter enter, UnitReason reason) {
        Building building = enter.building();
        if (reason instanceof UnitReason.Performed || building.isDead()) {
            return null;
        }
        if (view.isCloseEnough(0f, building)) {
            return view.canEnter(building) ? new Step<>(enter, new UnitPlan.Enter(building, true)) : null;
        }
        if (enter.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step<>(new Order.Enter(building, enter.attempts() + 1), new UnitPlan.Walk(building));
    }

    private @Nullable Step<Order, UnitPlan> build(Order.Build build) {
        Building building = build.building();
        if (building.isDead()) {
            return null;
        }
        if (building.isPlaced()) {
            return building.isDamaged() ? repair(new Order.Repair(building), UnitReason.BEGIN) : null;
        }
        float distance = building.getSize();
        if (view.isCloseEnough(distance, building)) {
            if (!building.isPlacingLegal()) {
                return null;
            }
            // Once placed, the site is built by repairing it.
            return new Step<>(new Order.Repair(building), new UnitPlan.Place(building));
        }
        if (build.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step<>(
                new Order.Build(building, build.attempts() + 1),
                new UnitPlan.Walk(building, distance, false));
    }

    private @Nullable Step<Order, UnitPlan> transfer(Order.Transfer transfer, UnitReason reason) {
        if (reason instanceof UnitReason.Performed) {
            return null;
        }
        Building found = transfer.found();
        if (reason instanceof UnitReason.FoundStore f) {
            found = f.store();
        }
        if (found != null && view.isCloseEnough(0f, found)) {
            if (!view.canEnter(found)) {
                return null;
            }
            return new Step<>(new Order.Transfer(found, transfer.attempts()), new UnitPlan.Enter(found, false));
        }
        if (transfer.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step<>(
                new Order.Transfer(found, transfer.attempts() + 1),
                new UnitPlan.FindStore());
    }
}
