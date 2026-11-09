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
 * activity yields, and waits for that otherwise. Urgent events (stuns, spells, death, mounting)
 * cancel the running activity and decide at once. A unit excused from its order decides nothing:
 * the order's activity is left to finish, and the unit decides once it is done. A posture's
 * activity is suspended rather than stopped, finished or not, and resumed when the posture runs
 * again, so it keeps its timers and boarding progress; it is discarded only when the posture is
 * replaced or the unit leaves.
 *
 * <p>A decision is made by the current task, for a {@link Reason}. A task that finishes hands over
 * to the slot beneath it: a nested task to its parent, an order to the posture, an interruption to
 * whatever it interrupted. An order can also become another order, which decides in the same call,
 * and a posture that spots an enemy gives itself one. {@link #decide} follows these handovers in
 * one call until a task has something to do.
 */
@NullMarked
public final class UnitStateReducer implements StateReducer<UnitState, Event, List<UnitCommand>> {
    /** How many walks a task makes towards something before it gives up on reaching it. */
    private static final int MAX_ATTEMPTS = 1;

    /**
     * How many times one decision may hand over between slots. A slot that takes over begins, and
     * only a posture that spotted an enemy gives itself an order, so a decision needs two at most:
     * from an interruption through the order to the posture, or from the posture to its own order
     * and back.
     */
    private static final int MAX_HANDOVERS = 2;

    /** What an order does next; a decision returns null once the order is done. */
    private record Step(Order next, UnitPlan plan) {
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
        // Nothing should reach a unit that has left the world; ignore it if something does.
        if (state.execution() instanceof UnitState.Execution.Gone) {
            assert false : "an event for a unit that has left the world";
            return withoutCommands(state);
        }
        TaskSlots tasks = state.tasks();
        // Nothing replaces dying, so a dying unit only follows its controller's reports.
        if (tasks.dying() != null && !(event instanceof Event.Report)) {
            return withoutCommands(state);
        }
        return switch (event) {
            case Event.Started _ -> {
                if (!(state.execution() instanceof UnitState.Execution.Unstarted)) {
                    assert false : "a unit started twice";
                    yield withoutCommands(state);
                }
                yield replan(state, tasks, UnitCommand.INTERRUPT, Reason.BEGIN);
            }
            case Event.Ordered ordered -> {
                // A new order lifts the excuse: from now on it waits for the activity only while
                // that is committed.
                if (state.execution() instanceof UnitState.Execution.Running running && running.leftToFinish()) {
                    state = state.withExecution(running.withLeftToFinish(false));
                }
                yield reconsiderWhenYielding(state, tasks.withOrder(ordered.order()));
            }
            case Event.Excused _ -> excuse(state);
            case Event.Stunned stunned -> reconsiderAtOnce(
                    state,
                    tasks.withStunned(new Interruption.Stunned(stunned.seconds()))
            );
            case Event.Cast cast -> {
                TaskSlots interrupted = cast.clearOrder() ? tasks.withOrder(null) : tasks;
                yield reconsiderAtOnce(state, interrupted.withCasting(new Interruption.Casting(cast.factory())));
            }
            case Event.Died _ -> reconsiderAtOnce(state, tasks.withOnlyDying());
            case Event.MountedTower _ -> reconsiderAtOnce(state, new TaskSlots(new Posture.Idle(false)));
            case Event.MountedShip mounted -> {
                Posture posture;
                if (mounted.allocation().getRole() == ShipAllocation.FIGHTING) {
                    posture = new Posture.Fighting(mounted.ship(), mounted.allocation());
                } else {
                    posture = new Posture.Sitting(mounted.ship(), mounted.allocation());
                }
                yield reconsiderAtOnce(state, new TaskSlots(posture));
            }
            case Event.Unmounted _ -> reconsiderAtOnce(state, new TaskSlots(new Posture.Idle(true)));
            case Event.Report.Progressed progressed -> {
                if (!(state.execution() instanceof UnitState.Execution.Running running)) {
                    yield withoutCommands(state);
                }
                yield reconsiderWhenYielding(state.withExecution(running.withProgress(progressed.progress())), tasks);
            }
            case Event.Report.Finished finished -> {
                if (!(state.execution() instanceof UnitState.Execution.Running running)) {
                    yield withoutCommands(state);
                }
                // A walk left to finish hunts what it spots directly, not via the posture, so no
                // walk back will follow.
                if (running.leftToFinish() && finished.outcome() instanceof Reason.Spotted spotted) {
                    yield replan(state, tasks.withOrder(new Order.Hunt(spotted.target())), UnitCommand.RELEASE,
                            Reason.BEGIN);
                }
                // A committed activity can outlive its task; the current task then begins instead.
                Reason reason = running.task() == tasks.current() ? finished.outcome() : Reason.BEGIN;
                yield replan(state, tasks, UnitCommand.RELEASE, reason);
            }
            // Events the operation raised are reduced first and may have moved the unit on, even
            // to another one.
            case Event.Report.Performed performed -> {
                if (
                    !(state.execution() instanceof UnitState.Execution.Performing performing)
                            || performing.operation() != performed.operation()
                ) {
                    yield withoutCommands(state);
                }
                yield replan(state, tasks, UnitCommand.INTERRUPT, Reason.PERFORMED);
            }
            case Event.Report.Removed _ -> {
                List<UnitCommand> commands = new ArrayList<>(2);
                if (state.execution() instanceof UnitState.Execution.Running) {
                    commands.add(UnitCommand.INTERRUPT);
                }
                commands.add(UnitCommand.DISCARD);
                yield new Transition<>(new UnitState(tasks, UnitState.Execution.GONE), commands);
            }
        };
    }

    private static Transition<UnitState, List<UnitCommand>> withoutCommands(UnitState state) {
        return new Transition<>(state, List.of());
    }

    /**
     * Gives the unit its next tasks and decides at once, unless the running activity already runs
     * for the current task.
     */
    private Transition<UnitState, List<UnitCommand>> reconsiderAtOnce(UnitState state, TaskSlots next) {
        // Tasks are compared by identity in both reconsider methods, on Finished and in replan;
        // see UnitState.Execution.Running.
        if (state.execution() instanceof UnitState.Execution.Running running && running.task() == next.current()) {
            return withoutCommands(state.withTasks(next));
        }
        return replan(state, next, UnitCommand.INTERRUPT, Reason.BEGIN);
    }

    /**
     * Gives the unit its next tasks and decides at once, unless the running activity already runs
     * for the current task, or is committed or left to finish and so carries on.
     */
    private Transition<UnitState, List<UnitCommand>> reconsiderWhenYielding(UnitState state, TaskSlots next) {
        if (state.execution() instanceof UnitState.Execution.Running running) {
            boolean sameTask = running.task() == next.current();
            boolean committed = running.progress() == Activity.State.COMMITTED;
            boolean leftToFinish = running.leftToFinish();
            if (sameTask || committed || leftToFinish) {
                return withoutCommands(state.withTasks(next));
            }
        }
        return replan(state, next, UnitCommand.INTERRUPT, Reason.BEGIN);
    }

    /**
     * Takes the order away without deciding: an activity running for it is left to finish, and a
     * decision already waiting on a committed activity still waits for it to yield.
     */
    private Transition<UnitState, List<UnitCommand>> excuse(UnitState state) {
        if (state.execution() instanceof UnitState.Execution.Running running
                && running.task() == state.tasks().order()) {
            state = state.withExecution(running.withLeftToFinish(true));
        }
        return reconsiderWhenYielding(state, state.tasks().withOrder(null));
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
                yield new UnitState.Execution.Performing(operation);
            }
        };
        return new Transition<>(new UnitState(tasks, execution), commands);
    }

    /**
     * Lets the current task decide, handing over to the slot beneath until one has something to
     * do.
     */
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
                if (decideOrder(order, reason) instanceof Step(Order next, UnitPlan plan)) {
                    return new Decision(tasks.withOrder(next), plan);
                }
                tasks = tasks.withOrder(null);
            } else {
                Order engage = null;
                if (reason instanceof Reason.Spotted spotted) {
                    engage = engage(tasks.posture(), spotted.target());
                }
                if (engage == null) {
                    return new Decision(tasks, decidePosture(tasks.posture()));
                }
                tasks = tasks.withOrder(engage);
            }
            reason = Reason.BEGIN;
        }
    }

    private static @Nullable UnitPlan decideInterruption(Interruption interruption, Reason reason) {
        // A stun or spell decides only as it begins; any later decision means it has ended.
        if (!(interruption instanceof Interruption.Dying) && !(reason instanceof Reason.Begin)) {
            return null;
        }
        return switch (interruption) {
            case Interruption.Dying _ -> new UnitPlan.Die();
            case Interruption.Stunned stunned -> new UnitPlan.Stun(stunned.seconds());
            case Interruption.Casting casting -> new UnitPlan.Cast(casting.factory());
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

    /**
     * The order the posture gives itself when its watch spots an enemy, or null when it ignores
     * it.
     */
    private @Nullable Order engage(Posture posture, Selectable<?> spotted) {
        return switch (posture) {
            // A unit that can move chases the enemy and then walks back to where it stood.
            case Posture.Idle idle when idle.canMove() -> new Order.Walk(
                    new LandscapeTarget(view.gridX(), view.gridY()),
                    true,
                    0,
                    new Order.Hunt(spotted)
            );
            case Posture.Idle _ -> new Order.Attack(spotted);
            case Posture.Fighting fighting -> new Order.Attack(
                    spotted,
                    new Seat(fighting.ship(), fighting.allocation())
            );
            case Posture.Sitting _ -> null;
        };
    }

    private @Nullable Step decideOrder(Order order, Reason reason) {
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

    private @Nullable Step march(Order.March march, Reason reason) {
        if (reason instanceof Reason.Spotted spotted) {
            // Breaking off to hunt restores the walk's budget, so it carries on once the hunt
            // ends.
            return march(march.withProgress(0, new Order.Hunt(spotted.target())), Reason.BEGIN);
        }
        Order engaged = march.engaged();
        if (engaged != null && decideOrder(engaged, reason) instanceof Step(Order next, UnitPlan plan)) {
            return new Step(march.withProgress(march.attempts(), next), plan);
        }
        if (march.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step(
                march.withProgress(march.attempts() + 1, null),
                new UnitPlan.Walk(march.target(), 0f, march.aggressive())
        );
    }

    private @Nullable Step hunt(Order.Hunt hunt) {
        Selectable<?> target = hunt.target();
        if (target.isDead()) {
            return null;
        }
        float range = view.range(target);
        if (view.isCloseEnough(range, target)) {
            return new Step(new Order.Hunt(target, 0), new UnitPlan.Attack(target, null));
        }
        if (hunt.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step(new Order.Hunt(target, hunt.attempts() + 1), new UnitPlan.Walk(target, range, false));
    }

    private @Nullable Step attack(Order.Attack attack) {
        if (attack.target().isDead() || !view.canReach(attack.target())) {
            return null;
        }
        return new Step(attack, new UnitPlan.Attack(attack.target(), attack.seat()));
    }

    private @Nullable Step gather(Order.Gather gather, Reason reason) {
        int approachAttempts = 0;
        int deliverAttempts = 0;
        switch (gather.phase()) {
            case Order.Gather.Approaching approaching -> approachAttempts = approaching.attempts();
            case Order.Gather.Harvesting harvesting -> {
                if (harvest(harvesting.harvest(),
                        reason) instanceof HarvestStep(Order.Harvest harvest, UnitPlan plan)) {
                    return new Step(
                            gather.withPhase(new Order.Gather.Harvesting(harvest, harvesting.attempts())),
                            plan
                    );
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

    private @Nullable Step approach(Order.Gather gather, int attempts) {
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
        return new Step(gather.withSupply(supply).withPhase(new Order.Gather.Approaching(attempts + 1)),
                new UnitPlan.Walk(supply));
    }

    private @Nullable Step deliver(Order.Gather gather, int attempts) {
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
            return new Step(
                    gather.withPhase(new Order.Gather.Depositing(store, attempts)),
                    new UnitPlan.Deposit(store)
            );
        }
        if (attempts >= MAX_ATTEMPTS) {
            return null;
        }
        Order.Gather next = gather.withPhase(new Order.Gather.Delivering(attempts + 1));
        if (assignedStanding) {
            return new Step(next, new UnitPlan.Walk(assigned.getEntrance()));
        }
        return new Step(next, new UnitPlan.FindStore());
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

    private @Nullable Step repair(Order.Repair repair, Reason reason) {
        Building building = repair.building();
        int fetchAttempts = 0;
        int mendAttempts = 0;
        switch (repair.phase()) {
            case Order.Repair.Fetching fetching -> {
                if (harvest(fetching.harvest(), reason) instanceof HarvestStep(Order.Harvest harvest, UnitPlan plan)) {
                    return new Step(
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
                    return new Step(
                            repair.withPhase(new Order.Repair.Mending(mendAttempts)),
                            new UnitPlan.Repair(building));
                }
                if (building.getAbilities().hasAbilities(Abilities.SUPPLY_CONTAINER) && view.sharesOwner(building)) {
                    return enter(new Order.Enter(building), Reason.BEGIN);
                }
                return null;
            }
            if (mendAttempts >= MAX_ATTEMPTS) {
                return null;
            }
            return new Step(
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
                Reason.BEGIN
        );
    }

    private @Nullable Step enter(Order.Enter enter, Reason reason) {
        Building building = enter.building();
        if (reason instanceof Reason.Performed || building.isDead()) {
            return null;
        }
        if (view.isCloseEnough(0f, building)) {
            return view.canEnter(building) ? new Step(enter, new UnitPlan.Enter(building, true)) : null;
        }
        if (enter.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step(new Order.Enter(building, enter.attempts() + 1), new UnitPlan.Walk(building));
    }

    private @Nullable Step build(Order.Build build) {
        Building building = build.building();
        if (building.isDead()) {
            return null;
        }
        if (building.isPlaced()) {
            return building.isDamaged() ? repair(new Order.Repair(building), Reason.BEGIN) : null;
        }
        float distance = building.getSize();
        if (view.isCloseEnough(distance, building)) {
            if (!building.isPlacingLegal()) {
                return null;
            }
            // Once placed, the site is built by repairing it.
            return new Step(new Order.Repair(building), new UnitPlan.Place(building));
        }
        if (build.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step(
                new Order.Build(building, build.attempts() + 1),
                new UnitPlan.Walk(building, distance, false));
    }

    private @Nullable Step transfer(Order.Transfer transfer, Reason reason) {
        if (reason instanceof Reason.Performed) {
            return null;
        }
        Building found = transfer.found();
        if (reason instanceof Reason.FoundStore f) {
            found = f.store();
        }
        if (found != null && view.isCloseEnough(0f, found)) {
            if (!view.canEnter(found)) {
                return null;
            }
            return new Step(new Order.Transfer(found, transfer.attempts()), new UnitPlan.Enter(found, false));
        }
        if (transfer.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Step(
                new Order.Transfer(found, transfer.attempts() + 1),
                new UnitPlan.FindStore());
    }
}
