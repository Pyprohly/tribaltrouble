package com.oddlabs.tt.model.task;

import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.ShipAllocation;
import com.oddlabs.tt.model.Supply;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A unit's state machine. {@link #reduce} applies an event to the unit's state and returns the
 * commands its controller carries out; it reads the unit through a {@link UnitView} and changes
 * nothing itself.
 *
 * <p>One rule governs when an activity is replaced: a decision is taken as soon as the running
 * activity yields, and waits for that otherwise. Urgent events (stuns, spells, death,
 * mounting) cancel the running activity and decide at once. A posture's activity is suspended
 * rather than stopped, finished or not, and resumed when the posture runs again, so it keeps its
 * timers and boarding progress; it is discarded only when the posture is replaced or the unit leaves.
 *
 * <p>A decision is made by the current task, for a {@link Reason}. A task that finishes hands
 * over to the slot beneath it: a nested task to its parent, an order to the posture, an interruption
 * to whatever it interrupted. An order can also become another order, which decides in the same
 * call, and a posture that spots an enemy gives itself one. {@link #decide} follows these handovers
 * in one call until a task has something to do.
 */
@NullMarked
public final class UnitStateReducer implements StateReducer<UnitState, Event, List<UnitCommand>> {
    /** How many walks a task makes towards something before it gives up on reaching it. */
    private static final int MAX_ATTEMPTS = 1;

    /**
     * How many times one decision may hand over between slots before it is taken to be looping. A
     * slot that takes over begins, and only a posture that spotted an enemy gives itself an order, so
     * a decision needs two at most: from an interruption through the order to the posture, or from the
     * posture to its own order and back.
     */
    private static final int MAX_HANDOVERS = 8;

    /** Tells {@link #reconsider} to replace the running activity even while it is committed. */
    private static final boolean AT_ONCE = true;

    /** Tells {@link #reconsider} to wait until the running activity is yielding. */
    private static final boolean WHEN_YIELDING = false;

    /** What an order does next, or that it is done. */
    private sealed interface Step {
        Step DONE = new Done();

        record Continue(Order next, UnitPlan plan) implements Step {
        }

        record Done() implements Step {
        }
    }

    /** The tasks a decision leaves, and the plan the reducer turns into commands. */
    private record Decision(TaskSlots tasks, UnitPlan plan) {
    }

    /** The harvest a gathering or repairing order continues with, and its plan. */
    private record HarvestStep(Order.Harvest next, UnitPlan plan) {
    }

    private final UnitView view;

    public UnitStateReducer(UnitView view) {
        this.view = view;
    }

    @Override
    public Transition<UnitState, List<UnitCommand>> reduce(UnitState state, Event event) {
        if (state.execution() instanceof UnitState.Execution.Gone) {
            return withoutCommands(state);
        }
        TaskSlots tasks = state.tasks();
        return switch (event) {
            case Event.Started _ -> {
                if (!(state.execution() instanceof UnitState.Execution.Unstarted)) {
                    yield withoutCommands(state);
                }
                yield replan(state, tasks, UnitCommand.INTERRUPT, Reason.BEGIN);
            }
            case Event.Ordered ordered -> reconsider(state, tasks.withOrder(ordered.order()), WHEN_YIELDING);
            case Event.Stunned stunned -> {
                if (tasks.dying() != null) {
                    yield withoutCommands(state);
                }
                yield reconsider(state, tasks.withStunned(new Interruption.Stunned(stunned.seconds())), AT_ONCE);
            }
            case Event.Cast cast -> {
                if (tasks.dying() != null) {
                    yield withoutCommands(state);
                }
                TaskSlots interrupted = cast.clearOrder() ? tasks.withOrder(null) : tasks;
                yield reconsider(state, interrupted.withCasting(new Interruption.Casting(cast.factory())), AT_ONCE);
            }
            case Event.Died _ -> reconsider(state, tasks.withOnlyDying(), AT_ONCE);
            case Event.MountedTower _ -> reconsider(state, new TaskSlots(new Posture.Idle(false)), AT_ONCE);
            case Event.MountedShip mounted -> {
                Posture posture = mounted.allocation().getRole() == ShipAllocation.FIGHTING ? new Posture.Fighting(
                        mounted.ship(), mounted.allocation()) : new Posture.Sitting(mounted.ship(),
                                mounted.allocation());
                yield reconsider(state, new TaskSlots(posture), AT_ONCE);
            }
            case Event.Unmounted _ -> reconsider(state, new TaskSlots(new Posture.Idle(true)), AT_ONCE);
            case Event.Report.Progressed progressed -> {
                if (!(state.execution() instanceof UnitState.Execution.Running running)) {
                    yield withoutCommands(state);
                }
                yield reconsider(state.withExecution(new UnitState.Execution.Running(running.task(),
                        progressed.progress())), tasks, WHEN_YIELDING);
            }
            case Event.Report.Finished finished -> {
                if (!(state.execution() instanceof UnitState.Execution.Running running)) {
                    yield withoutCommands(state);
                }
                // A committed activity can outlive its task; the current task then begins instead.
                Reason reason = running.task() == tasks.current() ? finished.outcome() : Reason.BEGIN;
                yield replan(state, tasks, UnitCommand.RELEASE, reason);
            }
            // An event that decided while the operation was carried out has already moved the unit on.
            case Event.Report.Performed _ -> {
                if (!(state.execution() instanceof UnitState.Execution.Performing)) {
                    yield withoutCommands(state);
                }
                yield replan(state, tasks, UnitCommand.INTERRUPT, Reason.PERFORMED);
            }
            case Event.Report.Removed _ -> new Transition<>(new UnitState(tasks, UnitState.Execution.GONE),
                    state.execution() instanceof UnitState.Execution.Running ? List.of(UnitCommand.INTERRUPT,
                            UnitCommand.DISCARD) : List.of(UnitCommand.DISCARD));
        };
    }

    private static Transition<UnitState, List<UnitCommand>> withoutCommands(UnitState state) {
        return new Transition<>(state, List.of());
    }

    /**
     * Gives the unit its next tasks, and decides at once unless the running activity is already the
     * current task's, or is committed and the change is not urgent.
     */
    private Transition<UnitState, List<UnitCommand>> reconsider(
            UnitState state,
            TaskSlots next,
            boolean urgent
    ) {
        if (state.execution() instanceof UnitState.Execution.Running running) {
            // Tasks are compared by identity here, on Finished and in replan; see UnitState.Execution.Running.
            boolean alreadyRunning = running.task() == next.current();
            boolean committed = running.progress() == Activity.State.COMMITTED;
            if (alreadyRunning || (committed && !urgent)) {
                return withoutCommands(state.withTasks(next));
            }
        }
        return replan(state, next, UnitCommand.INTERRUPT, Reason.BEGIN);
    }

    /**
     * Stops what runs with the stop command, discards the posture's activity when the posture was
     * replaced, plans for the next tasks, and starts or performs what the plan asks for.
     */
    private Transition<UnitState, List<UnitCommand>> replan(
            UnitState state,
            TaskSlots next,
            UnitCommand.Stop stop,
            Reason reason
    ) {
        List<UnitCommand> commands = new ArrayList<>(3);
        if (state.execution() instanceof UnitState.Execution.Running) {
            commands.add(stop);
        }
        if (next.posture() != state.tasks().posture()) {
            commands.add(UnitCommand.DISCARD);
        }
        Decision decision = decide(next, reason);
        TaskSlots tasks = decision.tasks();
        UnitState.Execution execution = switch (decision.plan()) {
            case UnitPlan.Run run -> {
                commands.add(new UnitCommand.Run(run));
                yield new UnitState.Execution.Running(tasks.current(), run.initialProgress());
            }
            case UnitPlan.Operation operation -> {
                commands.add(new UnitCommand.Perform(operation));
                yield UnitState.Execution.PERFORMING;
            }
        };
        return new Transition<>(new UnitState(tasks, execution), commands);
    }

    /** Lets the current task decide, handing over to the slot beneath until one has something to do. */
    private Decision decide(TaskSlots tasks, Reason reason) {
        for (int handovers = 0;; handovers++) {
            assert handovers <= MAX_HANDOVERS : "a decision that never ends in something to do";
            Interruption interruption = tasks.interruption();
            Order order = tasks.order();
            if (interruption != null) {
                UnitPlan plan = decideInterruption(interruption, reason);
                if (plan != null) {
                    return new Decision(tasks, plan);
                }
                tasks = tasks.withoutInterruption();
            } else if (order != null) {
                if (decideOrder(order, reason) instanceof Step.Continue(Order next, UnitPlan plan)) {
                    return new Decision(tasks.withOrder(next), plan);
                }
                tasks = tasks.withOrder(null);
            } else {
                Order engage = reason instanceof Reason.Spotted spotted ? engage(tasks.posture(),
                        spotted.target()) : null;
                if (engage == null) {
                    return new Decision(tasks, decidePosture(tasks.posture()));
                }
                tasks = tasks.withOrder(engage);
            }
            reason = Reason.BEGIN;
        }
    }

    private static @Nullable UnitPlan decideInterruption(Interruption interruption, Reason reason) {
        return switch (interruption) {
            case Interruption.Dying _ -> new UnitPlan.Die();
            case Interruption.Stunned stunned -> reason instanceof Reason.Begin ? new UnitPlan.Stun(
                    stunned.seconds()) : null;
            case Interruption.Casting casting -> reason instanceof Reason.Begin ? new UnitPlan.Cast(
                    casting.factory()) : null;
        };
    }

    /** What the posture does while nothing is above it. */
    private static UnitPlan.Lasting decidePosture(Posture posture) {
        return switch (posture) {
            case Posture.Idle idle -> new UnitPlan.Watch(idle.canMove());
            case Posture.Fighting fighting -> new UnitPlan.Fight(fighting.ship(), fighting.allocation());
            case Posture.Sitting sitting -> new UnitPlan.Sit(sitting.ship(), sitting.allocation());
        };
    }

    /** The order the posture gives itself when its watch spots an enemy, or null when it ignores it. */
    private @Nullable Order engage(Posture posture, Selectable<?> spotted) {
        return switch (posture) {
            // A unit that can move chases the enemy and then walks back to where it stood.
            case Posture.Idle idle -> idle.canMove() ? new Order.Walk(new LandscapeTarget(view.gridX(), view.gridY()),
                    true, 0, new Order.Hunt(spotted)) : new Order.Attack(spotted);
            case Posture.Fighting fighting -> new Order.Attack(spotted, new Seat(fighting.ship(),
                    fighting.allocation()));
            case Posture.Sitting _ -> null;
        };
    }

    private Step decideOrder(Order order, Reason reason) {
        if (reason instanceof Reason.Begin) {
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

    private Step march(Order.March march, Reason reason) {
        if (reason instanceof Reason.Spotted spotted) {
            // Breaking off to hunt restores the walk's budget, so it carries on once the hunt ends.
            return march(march.withProgress(0, new Order.Hunt(spotted.target())), Reason.BEGIN);
        }
        Order engaged = march.engaged();
        if (engaged != null && decideOrder(engaged, reason) instanceof Step.Continue(Order next, UnitPlan plan)) {
            return new Step.Continue(march.withProgress(march.attempts(), next), plan);
        }
        if (march.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return new Step.Continue(march.withProgress(march.attempts() + 1, null), new UnitPlan.Walk(march.target(), 0f,
                march.aggressive()));
    }

    private Step hunt(Order.Hunt hunt) {
        Selectable<?> target = hunt.target();
        if (target.isDead()) {
            return Step.DONE;
        }
        float range = view.range(target);
        if (view.isCloseEnough(range, target)) {
            return new Step.Continue(new Order.Hunt(target, 0), new UnitPlan.Attack(target, null));
        }
        if (hunt.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return new Step.Continue(new Order.Hunt(target, hunt.attempts() + 1), new UnitPlan.Walk(target, range, false));
    }

    private Step attack(Order.Attack attack) {
        if (attack.target().isDead() || !view.canReach(attack.target())) {
            return Step.DONE;
        }
        return new Step.Continue(attack, new UnitPlan.Attack(attack.target(), attack.seat()));
    }

    private Step gather(Order.Gather gather, Reason reason) {
        int approachAttempts = 0;
        int deliverAttempts = 0;
        switch (gather.phase()) {
            case Order.Gather.Approaching approaching -> approachAttempts = approaching.attempts();
            case Order.Gather.Harvesting harvesting -> {
                if (harvest(harvesting.harvest(),
                        reason) instanceof HarvestStep(Order.Harvest harvest, UnitPlan plan)) {
                    return new Step.Continue(gather.withPhase(new Order.Gather.Harvesting(harvest,
                            harvesting.attempts())), plan);
                }
                approachAttempts = harvesting.attempts();
            }
            case Order.Gather.Delivering delivering -> {
                if (reason instanceof Reason.FoundStore found) {
                    gather = gather.withFoundBuilding(found.store());
                }
                deliverAttempts = delivering.attempts();
            }
            case Order.Gather.Depositing depositing -> {
                if (reason instanceof Reason.Performed && view.carriesAnything()) {
                    // The store is full: move into it rather than carry the rest around.
                    return enter(new Order.Enter(depositing.store()), Reason.BEGIN);
                }
                deliverAttempts = depositing.attempts();
            }
        }
        if (view.carries(gather.supplyType())) {
            return deliver(gather, deliverAttempts);
        }
        return approach(gather, approachAttempts);
    }

    private Step approach(Order.Gather gather, int attempts) {
        Supply supply = gather.supply();
        if (supply != null && supply.isDead()) {
            supply = null;
            attempts = 0;
        }
        if (supply != null && view.isCloseEnough(view.range(supply), supply)) {
            return gather(gather.withSupply(supply).withPhase(new Order.Gather.Harvesting(new Order.Harvest(supply,
                    gather.supplyType(), 0), attempts)), Reason.BEGIN);
        }
        if (attempts >= MAX_ATTEMPTS) {
            return transfer(new Order.Transfer(), Reason.BEGIN);
        }
        if (supply == null) {
            return gather(gather.withSupply(null).withPhase(new Order.Gather.Harvesting(new Order.Harvest(null,
                    gather.supplyType(), 0), attempts + 1)), Reason.BEGIN);
        }
        return new Step.Continue(gather.withSupply(supply).withPhase(new Order.Gather.Approaching(attempts + 1)),
                new UnitPlan.Walk(supply));
    }

    private Step deliver(Order.Gather gather, int attempts) {
        Building assigned = gather.assignedBuilding();
        boolean assignedStanding = assigned != null && !assigned.isDead();
        Building found = gather.foundBuilding();
        Building store = assignedStanding ? assigned.getEntrance() : found != null ? found.getEntrance() : null;
        if (store != null && view.isCloseEnough(0f, store)) {
            return new Step.Continue(gather.withPhase(new Order.Gather.Depositing(store, attempts)),
                    new UnitPlan.Deposit(store));
        }
        if (attempts >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        Order.Gather next = gather.withPhase(new Order.Gather.Delivering(attempts + 1));
        if (assignedStanding) {
            return new Step.Continue(next, new UnitPlan.Walk(assigned.getEntrance()));
        }
        return new Step.Continue(next, new UnitPlan.FindStore());
    }

    /** What a harvest does next, or null when it is done. */
    private @Nullable HarvestStep harvest(Order.Harvest harvest, Reason reason) {
        if (view.isFull(harvest.supplyType())) {
            return null;
        }
        Supply supply = harvest.supply();
        if (reason instanceof Reason.FoundSupply found) {
            supply = found.supply();
        }
        if (supply != null && !supply.isEmpty() && view.isCloseEnough(0f, supply)) {
            return new HarvestStep(new Order.Harvest(supply, harvest.supplyType(), 0), new UnitPlan.Harvest(supply));
        }
        if (harvest.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new HarvestStep(new Order.Harvest(supply, harvest.supplyType(), harvest.attempts() + 1),
                new UnitPlan.FindSupply(harvest.supplyType()));
    }

    private Step repair(Order.Repair repair, Reason reason) {
        Building building = repair.building();
        int fetchAttempts = 0;
        int mendAttempts = 0;
        switch (repair.phase()) {
            case Order.Repair.Fetching fetching -> {
                if (harvest(fetching.harvest(), reason) instanceof HarvestStep(Order.Harvest harvest, UnitPlan plan)) {
                    return new Step.Continue(
                            repair.withPhase(new Order.Repair.Fetching(harvest, fetching.attempts())),
                            plan
                    );
                }
                fetchAttempts = fetching.attempts();
            }
            case Order.Repair.Mending mending -> mendAttempts = mending.attempts();
        }
        if (building.isDead()) {
            return Step.DONE;
        }
        if (view.carries(TreeSupply.class)) {
            if (view.isCloseEnough(0f, building)) {
                if (building.isDamaged()) {
                    return new Step.Continue(
                            repair.withPhase(new Order.Repair.Mending(mendAttempts)),
                            new UnitPlan.Repair(building));
                }
                if (building.getAbilities().hasAbilities(Abilities.SUPPLY_CONTAINER) && view.sharesOwner(building)) {
                    return enter(new Order.Enter(building), Reason.BEGIN);
                }
                return Step.DONE;
            }
            if (mendAttempts >= MAX_ATTEMPTS) {
                return Step.DONE;
            }
            return new Step.Continue(
                    repair.withPhase(new Order.Repair.Mending(mendAttempts + 1)),
                    new UnitPlan.Walk(building));
        }
        if (fetchAttempts >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return repair(
                repair.withPhase(
                        new Order.Repair.Fetching(
                                new Order.Harvest(null, TreeSupply.class, 0),
                                fetchAttempts + 1
                        )
                ),
                Reason.BEGIN
        );
    }

    private Step enter(Order.Enter enter, Reason reason) {
        Building building = enter.building();
        if (reason instanceof Reason.Performed || building.isDead()) {
            return Step.DONE;
        }
        if (view.isCloseEnough(0f, building)) {
            return view.canEnter(building) ? new Step.Continue(enter, new UnitPlan.Enter(building, true)) : Step.DONE;
        }
        if (enter.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return new Step.Continue(new Order.Enter(building, enter.attempts() + 1), new UnitPlan.Walk(building));
    }

    private Step build(Order.Build build) {
        Building building = build.building();
        if (building.isDead()) {
            return Step.DONE;
        }
        if (building.isPlaced()) {
            return building.isDamaged() ? repair(new Order.Repair(building), Reason.BEGIN) : Step.DONE;
        }
        float distance = building.getSize();
        if (view.isCloseEnough(distance, building)) {
            // Once placed, the site is built by repairing it.
            return building.isPlacingLegal() ? new Step.Continue(
                    new Order.Repair(building), new UnitPlan.Place(
                            building)) : Step.DONE;
        }
        if (build.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return new Step.Continue(
                new Order.Build(building, build.attempts() + 1),
                new UnitPlan.Walk(building, distance, false));
    }

    private Step transfer(Order.Transfer transfer, Reason reason) {
        if (reason instanceof Reason.Performed) {
            return Step.DONE;
        }
        Building found = transfer.found();
        if (reason instanceof Reason.FoundStore f) {
            found = f.store();
        }
        if (found != null && view.isCloseEnough(0f, found)) {
            return view.canEnter(found) ? new Step.Continue(
                    new Order.Transfer(found, transfer.attempts()),
                    new UnitPlan.Enter(found, false)) : Step.DONE;
        }
        if (transfer.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return new Step.Continue(
                new Order.Transfer(found, transfer.attempts() + 1),
                new UnitPlan.FindStore());
    }
}
