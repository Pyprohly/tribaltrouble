package com.oddlabs.tt.model.behaviour;

import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A unit's decisions. Each one takes the unit's state and the reason it is deciding, reads the world
 * through a {@link UnitView}, and returns the next state and what to do. Nothing here changes the
 * world or keeps state of its own.
 *
 * <p>A task that finishes hands over to the slot beneath it: a nested task to its parent, an order
 * to the posture, an interruption to whatever it interrupted. Each of those decides again in the
 * same call, so a decision always ends in something to do.
 */
@NullMarked
public final class UnitReducer {
    /** How many walks a task makes towards something before it gives up on reaching it. */
    private static final int MAX_ATTEMPTS = 1;

    public record Decision(UnitState state, UnitPlan plan) {
    }

    /** What an order does next, or that it is done. */
    private record Step(@Nullable Order order, @Nullable UnitPlan plan) {
        static final Step DONE = new Step(null, null);
    }

    /** What a nested harvest does next, or that it is done. */
    private record HarvestStep(Order.@Nullable Harvest harvest, UnitPlan.@Nullable Activity activity) {
        static final HarvestStep DONE = new HarvestStep(null, null);
    }

    @FunctionalInterface
    private interface Marching {
        Order rebuild(int attempts, Order.@Nullable Hunt engaged);
    }

    private UnitReducer() {
    }

    public static Decision decide(UnitState state, Event event, UnitView view) {
        Interruption interruption = state.interruption();
        if (interruption != null) {
            UnitPlan plan = interruption(interruption, event);
            if (plan != null) {
                return new Decision(state, plan);
            }
            return decide(state.withoutInterruption(), Event.BEGIN, view);
        }
        Order order = state.order();
        if (order != null) {
            Step step = order(event instanceof Event.Begin ? order.resetAttempts() : order, event, view);
            if (step.order() != null && step.plan() != null) {
                return new Decision(state.withOrder(step.order()), step.plan());
            }
            return posture(state.withOrder(null), Event.BEGIN, view);
        }
        return posture(state, event, view);
    }

    private static @Nullable UnitPlan interruption(Interruption interruption, Event event) {
        return switch (interruption) {
            case Interruption.Dying ignored -> new UnitPlan.Die();
            case Interruption.Stunned stunned -> event instanceof Event.Begin ? new UnitPlan.Stun(
                    stunned.seconds()) : null;
            case Interruption.Casting casting -> event instanceof Event.Begin ? new UnitPlan.Cast(
                    casting.factory()) : null;
        };
    }

    private static Decision posture(UnitState state, Event event, UnitView view) {
        Selectable<?> spotted = event instanceof Event.Spotted s ? s.target() : null;
        return switch (state.posture()) {
            case Posture.Idle idle -> {
                if (spotted == null) {
                    yield new Decision(state, new UnitPlan.Watch(idle.canMove()));
                }
                // A unit that can move chases the enemy and then walks back to where it stood.
                Order engage = idle.canMove() ? new Order.Walk(new LandscapeTarget(view.gridX(), view.gridY()), true, 0,
                        new Order.Hunt(spotted)) : new Order.Attack(spotted);
                yield decide(state.withOrder(engage), Event.BEGIN, view);
            }
            case Posture.Fighting fighting -> {
                if (spotted == null) {
                    yield new Decision(state, new UnitPlan.Fight(fighting.ship(), fighting.allocation()));
                }
                yield decide(state.withOrder(new Order.Attack(spotted, fighting.ship(), fighting.allocation())),
                        Event.BEGIN, view);
            }
            case Posture.Sitting sitting -> new Decision(state, new UnitPlan.Sit(sitting.ship(), sitting.allocation()));
        };
    }

    private static Step order(Order order, Event event, UnitView view) {
        return switch (order) {
            case Order.Walk walk -> march(walk.target(), walk.aggressive(), walk.attempts(), walk.engaged(), event,
                    view,
                    (attempts, engaged) -> new Order.Walk(walk.target(), walk.aggressive(), attempts, engaged));
            case Order.Defend defend -> march(defend.target(), true, defend.attempts(), defend.engaged(), event, view,
                    (attempts, engaged) -> new Order.Defend(defend.target(), attempts, engaged));
            case Order.Hunt hunt -> hunt(hunt, view);
            case Order.Attack attack -> attack(attack, view);
            case Order.Gather gather -> gather(gather, event, view);
            case Order.Repair repair -> repair(repair, event, view);
            case Order.Enter enter -> enter(enter, event, view);
            case Order.Build build -> build(build, view);
            case Order.Transfer transfer -> transfer(transfer, event, view);
        };
    }

    private static Step run(Order order, UnitPlan plan) {
        return new Step(order, plan);
    }

    private static Step march(Target target, boolean aggressive, int attempts, Order.@Nullable Hunt engaged,
            Event event, UnitView view, Marching marching) {
        if (event instanceof Event.Spotted spotted) {
            // Breaking off to hunt restores the walk's budget, so it carries on once the hunt ends.
            return march(target, aggressive, 0, new Order.Hunt(spotted.target()), Event.BEGIN, view, marching);
        }
        if (engaged != null) {
            Step hunting = hunt(engaged, view);
            if (hunting.order() instanceof Order.Hunt hunt && hunting.plan() != null) {
                return run(marching.rebuild(attempts, hunt), hunting.plan());
            }
        }
        if (attempts >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return run(marching.rebuild(attempts + 1, null), new UnitPlan.Walk(target, 0f, aggressive));
    }

    private static Step hunt(Order.Hunt hunt, UnitView view) {
        Selectable<?> target = hunt.target();
        if (view.isDead(target)) {
            return Step.DONE;
        }
        float range = view.range(target);
        if (view.isCloseEnough(range, target)) {
            return run(new Order.Hunt(target, 0), new UnitPlan.Attack(target, null, null));
        }
        if (hunt.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return run(new Order.Hunt(target, hunt.attempts() + 1), new UnitPlan.Walk(target, range, false));
    }

    private static Step attack(Order.Attack attack, UnitView view) {
        if (view.isDead(attack.target()) || !view.canReach(attack.target())) {
            return Step.DONE;
        }
        return run(attack, new UnitPlan.Attack(attack.target(), attack.ship(), attack.allocation()));
    }

    private static Step gather(Order.Gather gather, Event event, UnitView view) {
        Order.Harvest harvesting = gather.harvesting();
        if (harvesting != null) {
            HarvestStep step = harvest(harvesting, event, view);
            if (step.harvest() != null && step.activity() != null) {
                return run(withHarvesting(gather, step.harvest()), step.activity());
            }
            gather = withHarvesting(gather, null);
        } else if (event instanceof Event.Found found) {
            Building building = found.occupant() instanceof Building b ? b : null;
            gather = new Order.Gather(gather.supplyType(), gather.supply(), gather.assignedBuilding(), building, null,
                    gather.depositedAt(), gather.harvestAttempts(), gather.dropoffAttempts());
        }
        Building depositedAt = gather.depositedAt();
        if (depositedAt != null) {
            gather = new Order.Gather(gather.supplyType(), gather.supply(), gather.assignedBuilding(),
                    gather.foundBuilding(), null, null, gather.harvestAttempts(), gather.dropoffAttempts());
            if (event instanceof Event.Performed && view.carriesAnything()) {
                // The store is full: move into it rather than carry the rest around.
                return enter(new Order.Enter(depositedAt), Event.BEGIN, view);
            }
        }
        if (view.carries(gather.supplyType())) {
            return dropoff(gather, view);
        }
        return collect(gather, view);
    }

    private static Step collect(Order.Gather gather, UnitView view) {
        Supply supply = gather.supply();
        int attempts = gather.harvestAttempts();
        if (supply != null && view.isDead(supply)) {
            supply = null;
            attempts = 0;
        }
        if (supply != null && view.isCloseEnough(view.range(supply), supply)) {
            return gather(collecting(gather, supply, attempts, new Order.Harvest(supply, gather.supplyType(), 0)),
                    Event.BEGIN, view);
        }
        if (attempts >= MAX_ATTEMPTS) {
            return transfer(new Order.Transfer(), Event.BEGIN, view);
        }
        if (supply == null) {
            return gather(collecting(gather, null, attempts + 1, new Order.Harvest(null, gather.supplyType(), 0)),
                    Event.BEGIN, view);
        }
        return run(collecting(gather, supply, attempts + 1, null), new UnitPlan.Walk(supply, 0f, false));
    }

    private static Step dropoff(Order.Gather gather, UnitView view) {
        Building assigned = gather.assignedBuilding();
        boolean assignedStanding = assigned != null && !view.isDead(assigned);
        Building found = gather.foundBuilding();
        Building store = assignedStanding ? assigned.getEntrance() : found != null ? found.getEntrance() : null;
        if (store != null && view.isCloseEnough(0f, store)) {
            return new Step(dropping(gather, store, gather.dropoffAttempts()), new UnitPlan.Deposit(store));
        }
        if (gather.dropoffAttempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        Order.Gather next = dropping(gather, null, gather.dropoffAttempts() + 1);
        if (assignedStanding) {
            return run(next, new UnitPlan.Walk(assigned.getEntrance(), 0f, false));
        }
        return run(next, new UnitPlan.FindStore());
    }

    private static Order.Gather collecting(Order.Gather gather, @Nullable Supply supply, int harvestAttempts,
            Order.@Nullable Harvest harvesting) {
        return new Order.Gather(gather.supplyType(), supply, gather.assignedBuilding(), gather.foundBuilding(),
                harvesting, null, harvestAttempts, 0);
    }

    private static Order.Gather dropping(Order.Gather gather, @Nullable Building depositedAt, int dropoffAttempts) {
        return new Order.Gather(gather.supplyType(), gather.supply(), gather.assignedBuilding(),
                gather.foundBuilding(), null, depositedAt, 0, dropoffAttempts);
    }

    private static Order.Gather withHarvesting(Order.Gather gather, Order.@Nullable Harvest harvesting) {
        return new Order.Gather(gather.supplyType(), gather.supply(), gather.assignedBuilding(),
                gather.foundBuilding(), harvesting, gather.depositedAt(), gather.harvestAttempts(),
                gather.dropoffAttempts());
    }

    private static HarvestStep harvest(Order.Harvest harvest, Event event, UnitView view) {
        if (view.isFull(harvest.supplyType())) {
            return HarvestStep.DONE;
        }
        Supply supply = harvest.supply();
        if (event instanceof Event.Found found) {
            supply = found.occupant() instanceof Supply s ? s : null;
        }
        if (supply != null && !view.isEmpty(supply) && view.isCloseEnough(0f, supply)) {
            return new HarvestStep(new Order.Harvest(supply, harvest.supplyType(), 0), new UnitPlan.Harvest(supply));
        }
        if (harvest.attempts() >= MAX_ATTEMPTS) {
            return HarvestStep.DONE;
        }
        return new HarvestStep(new Order.Harvest(supply, harvest.supplyType(), harvest.attempts() + 1),
                new UnitPlan.FindSupply(harvest.supplyType()));
    }

    private static Step repair(Order.Repair repair, Event event, UnitView view) {
        Building building = repair.building();
        Order.Harvest harvesting = repair.harvesting();
        if (harvesting != null) {
            HarvestStep step = harvest(harvesting, event, view);
            if (step.harvest() != null && step.activity() != null) {
                return run(new Order.Repair(building, step.harvest(), repair.harvestAttempts(),
                        repair.repairAttempts()),
                        step.activity());
            }
            repair = new Order.Repair(building, null, repair.harvestAttempts(), repair.repairAttempts());
        }
        if (view.isDead(building)) {
            return Step.DONE;
        }
        if (view.carries(TreeSupply.class)) {
            if (view.isCloseEnough(0f, building)) {
                if (view.isDamaged(building)) {
                    return run(new Order.Repair(building, null, 0, repair.repairAttempts()),
                            new UnitPlan.Repair(building));
                }
                if (view.storesSupplies(building) && view.sharesOwner(building)) {
                    return enter(new Order.Enter(building), Event.BEGIN, view);
                }
                return Step.DONE;
            }
            if (repair.repairAttempts() >= MAX_ATTEMPTS) {
                return Step.DONE;
            }
            return run(new Order.Repair(building, null, 0, repair.repairAttempts() + 1),
                    new UnitPlan.Walk(building, 0f, false));
        }
        if (repair.harvestAttempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return repair(new Order.Repair(building, new Order.Harvest(null, TreeSupply.class, 0),
                repair.harvestAttempts() + 1, 0), Event.BEGIN, view);
    }

    private static Step enter(Order.Enter enter, Event event, UnitView view) {
        Building building = enter.building();
        if (event instanceof Event.Performed || view.isDead(building)) {
            return Step.DONE;
        }
        if (view.isCloseEnough(0f, building)) {
            return view.canEnter(building) ? new Step(enter, new UnitPlan.Enter(building, true)) : Step.DONE;
        }
        if (enter.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return run(new Order.Enter(building, enter.attempts() + 1), new UnitPlan.Walk(building, 0f, false));
    }

    private static Step build(Order.Build build, UnitView view) {
        Building building = build.building();
        if (view.isDead(building)) {
            return Step.DONE;
        }
        if (view.isPlaced(building)) {
            return view.isDamaged(building) ? repair(new Order.Repair(building), Event.BEGIN, view) : Step.DONE;
        }
        float distance = view.placingDistance(building);
        if (view.isCloseEnough(distance, building)) {
            // Once placed, the site is built by repairing it.
            return view.isPlacingLegal(building) ? new Step(new Order.Repair(building), new UnitPlan.Place(
                    building)) : Step.DONE;
        }
        if (build.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return run(new Order.Build(building, build.attempts() + 1), new UnitPlan.Walk(building, distance, false));
    }

    private static Step transfer(Order.Transfer transfer, Event event, UnitView view) {
        if (event instanceof Event.Performed) {
            return Step.DONE;
        }
        Building found = transfer.found();
        if (event instanceof Event.Found f) {
            found = f.occupant() instanceof Building b ? b : null;
        }
        if (found != null && view.isCloseEnough(0f, found)) {
            return view.canEnter(found) ? new Step(new Order.Transfer(found, transfer.attempts()),
                    new UnitPlan.Enter(found, false)) : Step.DONE;
        }
        if (transfer.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return run(new Order.Transfer(found, transfer.attempts() + 1), new UnitPlan.FindStore());
    }
}
