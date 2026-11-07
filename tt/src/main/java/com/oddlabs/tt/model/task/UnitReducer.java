package com.oddlabs.tt.model.task;

import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.landscape.TreeSupply;
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
 * commands its controller carries out; it reads the world through a {@link UnitView} and changes
 * nothing itself.
 *
 * <p>One rule governs when an activity is replaced: a decision is taken as soon as the running
 * activity is interruptible, and waits for that otherwise. Urgent events (stuns, spells, death,
 * mounting) cancel the running activity and decide at once. The controller keeps a posture's
 * activity while an order or interruption runs above it, so stopping and restarting the posture
 * resumes it with its timers and boarding progress.
 *
 * <p>A decision is made by the current task, for a {@link Reason}. A task that finishes hands
 * over to the slot beneath it: a nested task to its parent, an order to the posture, an interruption
 * to whatever it interrupted. An order can also become another order, and a posture that spots an
 * enemy gives itself one. {@link #decide} follows these handovers in one call until a task has
 * something to do.
 */
@NullMarked
public final class UnitReducer implements StateReducer<UnitState, Event, List<UnitCommand>> {
    /** How many walks a task makes towards something before it gives up on reaching it. */
    private static final int MAX_ATTEMPTS = 1;

    /**
     * How many times one decision may hand over between tasks before it is taken to be looping. Orders
     * only hand over forwards, build to repair to enter and gather to enter or transfer, so a decision
     * needs a handful at most.
     */
    private static final int MAX_HANDOVERS = 8;

    /** What an order does next, that it hands over to another order, or that it is done. */
    private sealed interface Step {
        Step DONE = new Done();

        record Continue(Order next, UnitPlan plan) implements Step {
        }

        /** The order is replaced by the next one, which decides at once. */
        record Become(Order next) implements Step {
        }

        record Done() implements Step {
        }
    }

    private final UnitView view;

    public UnitReducer(UnitView view) {
        this.view = view;
    }

    @Override
    public Transition<UnitState, List<UnitCommand>> reduce(UnitState state, Event event) {
        if (state.execution() instanceof UnitState.Execution.Gone) {
            return unchanged(state);
        }
        TaskStack tasks = state.tasks();
        Interruption current = tasks.interruption();
        return switch (event) {
            case Event.Started ignored -> state.execution() instanceof UnitState.Execution.Unstarted ? replanNow(state,
                    Reason.BEGIN) : unchanged(state);
            case Event.Ordered ordered -> replanWhenInterruptible(state.withTasks(tasks.withOrder(ordered.order())));
            case Event.Stunned stunned -> {
                if (current instanceof Interruption.Dying) {
                    yield unchanged(state);
                }
                Interruption stun = new Interruption.Stunned(stunned.seconds());
                yield replanNow(
                        state.withTasks(
                                current instanceof Interruption.Stunned ? tasks.replacingInterruption(
                                        stun) : tasks.interruptedBy(stun)),
                        Reason.BEGIN
                );
            }
            case Event.Cast cast -> {
                if (current instanceof Interruption.Dying) {
                    yield unchanged(state);
                }
                TaskStack next = cast.clearOrder() ? tasks.withOrder(null) : tasks;
                Interruption casting = new Interruption.Casting(cast.factory());
                if (current instanceof Interruption.Stunned) {
                    yield replanWhenInterruptible(state.withTasks(next.followedBy(casting)));
                }
                yield replanNow(state.withTasks(next.interruptedBy(casting)), Reason.BEGIN);
            }
            case Event.Died ignored -> replanNow(state.withTasks(new TaskStack(tasks.posture(), null, List.of(
                    new Interruption.Dying()))), Reason.BEGIN);
            case Event.MountedTower ignored -> replanNow(state.withTasks(new TaskStack(new Posture.Idle(false))),
                    Reason.BEGIN);
            case Event.MountedShip mounted -> replanNow(state.withTasks(new TaskStack(
                    mounted.allocation().getRole() == ShipAllocation.FIGHTING ? new Posture.Fighting(mounted.ship(),
                            mounted.allocation()) : new Posture.Sitting(mounted.ship(), mounted.allocation()))),
                    Reason.BEGIN);
            case Event.Unmounted ignored -> replanNow(state.withTasks(new TaskStack(new Posture.Idle(true))),
                    Reason.BEGIN);
            case Event.Report.Progressed progressed -> {
                if (!(state.execution() instanceof UnitState.Execution.Running running)) {
                    yield unchanged(state);
                }
                yield replanWhenInterruptible(new UnitState(tasks, new UnitState.Execution.Running(running.task(),
                        progressed.progress())));
            }
            case Event.Report.Finished finished ->
                state.execution() instanceof UnitState.Execution.Running running ? finish(
                        state,
                        running, finished.outcome()) : unchanged(state);
            // An event that decided while the operation was carried out has already moved the unit on.
            case Event.Report.Performed ignored ->
                state.execution() instanceof UnitState.Execution.Performing ? replanNow(state,
                        Reason.PERFORMED) : unchanged(state);
            case Event.Report.Removed ignored -> new Transition<>(new UnitState(tasks, UnitState.Execution.GONE),
                    List.of(
                            UnitCommand.LEAVE));
        };
    }

    private static Transition<UnitState, List<UnitCommand>> unchanged(UnitState state) {
        return new Transition<>(state, List.of());
    }

    /** Replans if the current task is not the one running and the running activity can be interrupted. */
    private Transition<UnitState, List<UnitCommand>> replanWhenInterruptible(UnitState state) {
        // Tasks are compared by identity here and in finish; see UnitState.Execution.Running.
        if (state.execution() instanceof UnitState.Execution.Running running
                && (running.task() == state.tasks().current()
                        || running.progress() == Activity.State.UNINTERRUPTIBLE)) {
            return unchanged(state);
        }
        return replanNow(state, Reason.BEGIN);
    }

    private Transition<UnitState, List<UnitCommand>> finish(UnitState state, UnitState.Execution.Running running,
            Reason outcome) {
        Reason reason = state.tasks().current() == running.task() ? outcome : Reason.BEGIN;
        return replan(state, UnitCommand.RELEASE, reason);
    }

    private Transition<UnitState, List<UnitCommand>> replanNow(UnitState state, Reason reason) {
        return replan(state, UnitCommand.CANCEL, reason);
    }

    /** Stops what runs with the stop command, plans, and starts or performs what the plan asks for. */
    private Transition<UnitState, List<UnitCommand>> replan(UnitState state, UnitCommand stop, Reason reason) {
        List<UnitCommand> commands = new ArrayList<>(2);
        if (state.execution() instanceof UnitState.Execution.Running) {
            commands.add(stop);
        }
        Decision<TaskStack, UnitPlan> decision = decide(state.tasks(), reason);
        TaskStack tasks = decision.state();
        return switch (decision.plan()) {
            case UnitPlan.Run run -> {
                Task task = tasks.current();
                commands.add(new UnitCommand.Start(task, run));
                yield new Transition<>(new UnitState(tasks, new UnitState.Execution.Running(task,
                        run.initialProgress())),
                        commands);
            }
            case UnitPlan.Operation operation -> {
                commands.add(new UnitCommand.Perform(operation));
                yield new Transition<>(new UnitState(tasks, UnitState.Execution.PERFORMING), commands);
            }
        };
    }

    private Decision<TaskStack, UnitPlan> decide(TaskStack state, Reason reason) {
        for (int handovers = 0;; handovers++) {
            assert handovers <= MAX_HANDOVERS : "a decision that never ends in something to do";
            Interruption interruption = state.interruption();
            Order order = state.order();
            if (interruption != null) {
                UnitPlan plan = interruption(interruption, reason);
                if (plan != null) {
                    return new Decision<>(state, plan);
                }
                state = state.withoutInterruption();
            } else if (order != null) {
                switch (order(reason instanceof Reason.Begin ? order.withAttemptsReset() : order, reason)) {
                    case Step.Continue(Order next, UnitPlan plan) -> {
                        return new Decision<>(state.withOrder(next), plan);
                    }
                    case Step.Become(Order next) -> state = state.withOrder(next);
                    case Step.Done ignored -> state = state.withOrder(null);
                }
            } else {
                Selectable<?> spotted = reason instanceof Reason.Spotted s ? s.target() : null;
                Order engage = spotted != null ? engage(state.posture(), spotted) : null;
                if (engage == null) {
                    return new Decision<>(state, watch(state.posture()));
                }
                state = state.withOrder(engage);
            }
            reason = Reason.BEGIN;
        }
    }

    private @Nullable UnitPlan interruption(Interruption interruption, Reason reason) {
        return switch (interruption) {
            case Interruption.Dying ignored -> new UnitPlan.Die();
            case Interruption.Stunned stunned -> reason instanceof Reason.Begin ? new UnitPlan.Stun(
                    stunned.seconds()) : null;
            case Interruption.Casting casting -> reason instanceof Reason.Begin ? new UnitPlan.Cast(
                    casting.factory()) : null;
        };
    }

    /** What the posture does while nothing is above it. */
    private static UnitPlan watch(Posture posture) {
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
            case Posture.Sitting ignored -> null;
        };
    }

    private Step order(Order order, Reason reason) {
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
        Order.Hunt engaged = march.engaged();
        if (engaged != null && hunt(engaged) instanceof Step.Continue(Order.Hunt hunt, UnitPlan plan)) {
            return new Step.Continue(march.withProgress(march.attempts(), hunt), plan);
        }
        if (march.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return new Step.Continue(march.withProgress(march.attempts() + 1, null), new UnitPlan.Walk(march.target(), 0f,
                march.aggressive()));
    }

    private Step hunt(Order.Hunt hunt) {
        Selectable<?> target = hunt.target();
        if (view.isDead(target)) {
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
        if (view.isDead(attack.target()) || !view.canReach(attack.target())) {
            return Step.DONE;
        }
        return new Step.Continue(attack, new UnitPlan.Attack(attack.target(), attack.seat()));
    }

    private Step gather(Order.Gather gather, Reason reason) {
        switch (gather.phase()) {
            case Order.Gather.Harvesting harvesting -> {
                if (harvest(harvesting.harvest(),
                        reason) instanceof Decision<Order.Harvest, UnitPlan>(Order.Harvest harvest, UnitPlan plan)) {
                    return new Step.Continue(gather.withPhase(new Order.Gather.Harvesting(harvest,
                            harvesting.attempts())), plan);
                }
                gather = gather.withPhase(new Order.Gather.Approaching(harvesting.attempts()));
            }
            case Order.Gather.Depositing depositing -> {
                gather = gather.withPhase(new Order.Gather.Delivering(depositing.attempts()));
                if (reason instanceof Reason.Performed && view.carriesAnything()) {
                    // The store is full: move into it rather than carry the rest around.
                    return new Step.Become(new Order.Enter(depositing.store()));
                }
            }
            case Order.Gather.Delivering ignored -> {
                if (reason instanceof Reason.FoundStore found) {
                    gather = gather.withFoundBuilding(found.store());
                }
            }
            case Order.Gather.Approaching ignored -> {
            }
        }
        if (view.carries(gather.supplyType())) {
            return dropoff(gather, gather.phase() instanceof Order.Gather.Delivering d ? d.attempts() : 0);
        }
        return collect(gather, gather.phase() instanceof Order.Gather.Approaching a ? a.attempts() : 0);
    }

    private Step collect(Order.Gather gather, int attempts) {
        Supply supply = gather.supply();
        if (supply != null && view.isDead(supply)) {
            supply = null;
            attempts = 0;
        }
        if (supply != null && view.isCloseEnough(view.range(supply), supply)) {
            return gather(gather.withSupply(supply).withPhase(new Order.Gather.Harvesting(new Order.Harvest(supply,
                    gather.supplyType(), 0), attempts)), Reason.BEGIN);
        }
        if (attempts >= MAX_ATTEMPTS) {
            return new Step.Become(new Order.Transfer());
        }
        if (supply == null) {
            return gather(gather.withSupply(null).withPhase(new Order.Gather.Harvesting(new Order.Harvest(null,
                    gather.supplyType(), 0), attempts + 1)), Reason.BEGIN);
        }
        return new Step.Continue(gather.withSupply(supply).withPhase(new Order.Gather.Approaching(attempts + 1)),
                new UnitPlan.Walk(supply, 0f, false));
    }

    private Step dropoff(Order.Gather gather, int attempts) {
        Building assigned = gather.assignedBuilding();
        boolean assignedStanding = assigned != null && !view.isDead(assigned);
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
            return new Step.Continue(next, new UnitPlan.Walk(assigned.getEntrance(), 0f, false));
        }
        return new Step.Continue(next, new UnitPlan.FindStore());
    }

    /** What a harvest does next, or null when it is done. */
    private @Nullable Decision<Order.Harvest, UnitPlan> harvest(Order.Harvest harvest, Reason reason) {
        if (view.isFull(harvest.supplyType())) {
            return null;
        }
        Supply supply = harvest.supply();
        if (reason instanceof Reason.FoundSupply found) {
            supply = found.supply();
        }
        if (supply != null && !view.isEmpty(supply) && view.isCloseEnough(0f, supply)) {
            return new Decision<>(new Order.Harvest(supply, harvest.supplyType(), 0), new UnitPlan.Harvest(supply));
        }
        if (harvest.attempts() >= MAX_ATTEMPTS) {
            return null;
        }
        return new Decision<>(new Order.Harvest(supply, harvest.supplyType(), harvest.attempts() + 1),
                new UnitPlan.FindSupply(harvest.supplyType()));
    }

    private Step repair(Order.Repair repair, Reason reason) {
        Building building = repair.building();
        int fetchAttempts = 0;
        int mendAttempts = 0;
        switch (repair.phase()) {
            case Order.Repair.Fetching fetching -> {
                if (harvest(fetching.harvest(),
                        reason) instanceof Decision<Order.Harvest, UnitPlan>(Order.Harvest harvest, UnitPlan plan)) {
                    return new Step.Continue(new Order.Repair(building, new Order.Repair.Fetching(harvest,
                            fetching.attempts())),
                            plan);
                }
                fetchAttempts = fetching.attempts();
            }
            case Order.Repair.Mending mending -> mendAttempts = mending.attempts();
        }
        if (view.isDead(building)) {
            return Step.DONE;
        }
        if (view.carries(TreeSupply.class)) {
            if (view.isCloseEnough(0f, building)) {
                if (view.isDamaged(building)) {
                    return new Step.Continue(new Order.Repair(building, new Order.Repair.Mending(mendAttempts)),
                            new UnitPlan.Repair(building));
                }
                if (view.storesSupplies(building) && view.sharesOwner(building)) {
                    return new Step.Become(new Order.Enter(building));
                }
                return Step.DONE;
            }
            if (mendAttempts >= MAX_ATTEMPTS) {
                return Step.DONE;
            }
            return new Step.Continue(new Order.Repair(building, new Order.Repair.Mending(mendAttempts + 1)),
                    new UnitPlan.Walk(
                            building, 0f, false));
        }
        if (fetchAttempts >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return repair(new Order.Repair(building, new Order.Repair.Fetching(new Order.Harvest(null, TreeSupply.class,
                0), fetchAttempts + 1)), Reason.BEGIN);
    }

    private Step enter(Order.Enter enter, Reason reason) {
        Building building = enter.building();
        if (reason instanceof Reason.Performed || view.isDead(building)) {
            return Step.DONE;
        }
        if (view.isCloseEnough(0f, building)) {
            return view.canEnter(building) ? new Step.Continue(enter, new UnitPlan.Enter(building, true)) : Step.DONE;
        }
        if (enter.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return new Step.Continue(new Order.Enter(building, enter.attempts() + 1), new UnitPlan.Walk(building, 0f,
                false));
    }

    private Step build(Order.Build build) {
        Building building = build.building();
        if (view.isDead(building)) {
            return Step.DONE;
        }
        if (view.isPlaced(building)) {
            return view.isDamaged(building) ? new Step.Become(new Order.Repair(building)) : Step.DONE;
        }
        float distance = view.placingDistance(building);
        if (view.isCloseEnough(distance, building)) {
            // Once placed, the site is built by repairing it.
            return view.isPlacingLegal(building) ? new Step.Continue(new Order.Repair(building), new UnitPlan.Place(
                    building)) : Step.DONE;
        }
        if (build.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return new Step.Continue(new Order.Build(building, build.attempts() + 1), new UnitPlan.Walk(building, distance,
                false));
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
            return view.canEnter(found) ? new Step.Continue(new Order.Transfer(found, transfer.attempts()),
                    new UnitPlan.Enter(found,
                            false)) : Step.DONE;
        }
        if (transfer.attempts() >= MAX_ATTEMPTS) {
            return Step.DONE;
        }
        return new Step.Continue(new Order.Transfer(found, transfer.attempts() + 1), new UnitPlan.FindStore());
    }
}
