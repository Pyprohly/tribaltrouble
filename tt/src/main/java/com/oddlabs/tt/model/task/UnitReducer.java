package com.oddlabs.tt.model.task;

import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.ShipAllocation;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.util.Target;
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
 * mounting) cancel the running activity and decide at once. A posture's activity is never
 * cancelled while the posture lasts: it is parked under the order or interruption above it and
 * resumed afterwards, keeping its timers and boarding progress.
 *
 * <p>A decision is made by the current task, for a {@link Reason}. A task that finishes hands over
 * to the slot beneath it: a nested task to its parent, an order to the posture, an interruption to
 * whatever it interrupted. Each of those decides again in the same call, so a decision always ends
 * in something to do.
 */
@NullMarked
public final class UnitReducer implements StateReducer<UnitState, Event, List<UnitCommand>> {
    /** How many walks a task makes towards something before it gives up on reaching it. */
    private static final int MAX_ATTEMPTS = 1;

    /** What a task does next, or that it is done. */
    private sealed interface Step<O> {
        record Continue<O>(O next, UnitPlan plan) implements Step<O> {
        }

        record Done<O>() implements Step<O> {
        }
    }

    @FunctionalInterface
    private interface Marching {
        Order rebuild(int attempts, Order.@Nullable Hunt engaged);
    }

    private final UnitView view;

    public UnitReducer(UnitView view) {
        this.view = view;
    }

    @Override
    public Transition<UnitState, List<UnitCommand>> reduce(UnitState state, Event event) {
        if (state.slot() instanceof UnitState.Slot.Gone) {
            return unchanged(state);
        }
        TaskStack tasks = state.tasks();
        Interruption current = tasks.interruption();
        return switch (event) {
            case Event.Started ignored -> state.slot() instanceof UnitState.Slot.Vacant ? decideNow(state,
                    Reason.BEGIN) : unchanged(state);
            case Event.Ordered ordered -> decideWhenInterruptible(state.withTasks(tasks.withOrder(ordered.order())));
            case Event.Stunned stunned -> {
                if (current instanceof Interruption.Dying) {
                    yield unchanged(state);
                }
                Interruption stun = new Interruption.Stunned(stunned.seconds());
                yield decideNow(state.withTasks(current instanceof Interruption.Stunned ? tasks.replacingInterruption(
                        stun) : tasks.interruptedBy(stun)), Reason.BEGIN);
            }
            case Event.Cast cast -> {
                if (current instanceof Interruption.Dying) {
                    yield unchanged(state);
                }
                TaskStack next = cast.clearOrder() ? tasks.withOrder(null) : tasks;
                Interruption casting = new Interruption.Casting(cast.factory());
                if (current instanceof Interruption.Stunned) {
                    yield decideWhenInterruptible(state.withTasks(next.followedBy(casting)));
                }
                yield decideNow(state.withTasks(next.interruptedBy(casting)), Reason.BEGIN);
            }
            case Event.Died ignored -> decideNow(state.withTasks(new TaskStack(tasks.posture(), null, List.of(
                    new Interruption.Dying()))), Reason.BEGIN);
            case Event.MountedTower ignored -> decideNow(state.withTasks(new TaskStack(new Posture.Idle(false))),
                    Reason.BEGIN);
            case Event.MountedShip mounted -> decideNow(state.withTasks(new TaskStack(
                    mounted.allocation().getRole() == ShipAllocation.FIGHTING ? new Posture.Fighting(mounted.ship(),
                            mounted.allocation()) : new Posture.Sitting(mounted.ship(), mounted.allocation()))),
                    Reason.BEGIN);
            case Event.Unmounted ignored -> decideNow(state.withTasks(new TaskStack(new Posture.Idle(true))),
                    Reason.BEGIN);
            case Event.Progressed progressed -> {
                if (!(state.slot() instanceof UnitState.Slot.Running running)) {
                    yield unchanged(state);
                }
                // Reported every frame, so the state is only rebuilt when the progress changed.
                yield decideWhenInterruptible(running.progress() == progressed.progress() ? state : new UnitState(
                        tasks, new UnitState.Slot.Running(running.task(), progressed.progress()), state.parked()));
            }
            case Event.Finished finished -> state.slot() instanceof UnitState.Slot.Running running ? finish(state,
                    running, finished.outcome()) : unchanged(state);
            // An event that decided while the operation was carried out has already moved the unit on.
            case Event.Performed ignored -> state.slot() instanceof UnitState.Slot.Performing ? decideNow(state,
                    Reason.PERFORMED) : unchanged(state);
            case Event.Removed ignored -> {
                List<UnitCommand> commands = new ArrayList<>(2);
                if (state.slot() instanceof UnitState.Slot.Running) {
                    commands.add(UnitCommand.RELEASE);
                }
                if (state.parked() != null) {
                    commands.add(UnitCommand.DISCARD);
                }
                yield new Transition<>(new UnitState(tasks, UnitState.Slot.GONE, null), commands);
            }
        };
    }

    private static Transition<UnitState, List<UnitCommand>> unchanged(UnitState state) {
        return new Transition<>(state, List.of());
    }

    /** Decides if the current task is not the one running and the running activity can be interrupted. */
    private Transition<UnitState, List<UnitCommand>> decideWhenInterruptible(UnitState state) {
        if (state.slot() instanceof UnitState.Slot.Running running && (running.task() == state.tasks().current()
                || running.progress() == Activity.State.UNINTERRUPTIBLE)) {
            return unchanged(state);
        }
        return decideNow(state, Reason.BEGIN);
    }

    private Transition<UnitState, List<UnitCommand>> finish(UnitState state, UnitState.Slot.Running running,
            Reason outcome) {
        Reason reason = state.tasks().current() == running.task() ? outcome : Reason.BEGIN;
        return decide(state, UnitCommand.RELEASE, reason);
    }

    private Transition<UnitState, List<UnitCommand>> decideNow(UnitState state, Reason reason) {
        return decide(state, UnitCommand.CANCEL, reason);
    }

    /**
     * Stops what runs, parking a posture's activity and otherwise giving the stop command, decides,
     * and starts or resumes what the decision asks for.
     */
    private Transition<UnitState, List<UnitCommand>> decide(UnitState state, UnitCommand stop, Reason reason) {
        List<UnitCommand> commands = new ArrayList<>(3);
        Posture parked = state.parked();
        if (state.slot() instanceof UnitState.Slot.Running running) {
            if (running.task() == state.tasks().posture()) {
                commands.add(UnitCommand.PARK);
                parked = state.tasks().posture();
            } else {
                commands.add(stop);
            }
        }
        Transition<TaskStack, UnitPlan> decision = decide(state.tasks(), reason);
        TaskStack tasks = decision.nextState();
        if (parked != null && parked != tasks.posture()) {
            commands.add(UnitCommand.DISCARD);
            parked = null;
        }
        return switch (decision.effect()) {
            case UnitPlan.Run run -> {
                Task task = tasks.current();
                if (task == parked) {
                    commands.add(UnitCommand.RESUME);
                    parked = null;
                } else {
                    commands.add(new UnitCommand.Start(run));
                }
                yield new Transition<>(new UnitState(tasks, new UnitState.Slot.Running(task, run.initialProgress()),
                        parked), commands);
            }
            case UnitPlan.Operation operation -> {
                commands.add(new UnitCommand.Perform(operation));
                yield new Transition<>(new UnitState(tasks, UnitState.Slot.PERFORMING, parked), commands);
            }
        };
    }

    private Transition<TaskStack, UnitPlan> decide(TaskStack state, Reason reason) {
        Interruption interruption = state.interruption();
        if (interruption != null) {
            UnitPlan plan = interruption(interruption, reason);
            if (plan != null) {
                return new Transition<>(state, plan);
            }
            return decide(state.withoutInterruption(), Reason.BEGIN);
        }
        Order order = state.order();
        if (order != null) {
            if (order(reason instanceof Reason.Begin ? order.resetAttempts() : order,
                    reason) instanceof Step.Continue<Order>(Order next, UnitPlan plan)) {
                return new Transition<>(state.withOrder(next), plan);
            }
            return posture(state.withOrder(null), Reason.BEGIN);
        }
        return posture(state, reason);
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

    private Transition<TaskStack, UnitPlan> posture(TaskStack state, Reason reason) {
        Selectable<?> spotted = reason instanceof Reason.Spotted s ? s.target() : null;
        return switch (state.posture()) {
            case Posture.Idle idle -> {
                if (spotted == null) {
                    yield new Transition<>(state, new UnitPlan.Watch(idle.canMove()));
                }
                // A unit that can move chases the enemy and then walks back to where it stood.
                Order engage = idle.canMove() ? new Order.Walk(new LandscapeTarget(view.gridX(), view.gridY()), true, 0,
                        new Order.Hunt(spotted)) : new Order.Attack(spotted);
                yield decide(state.withOrder(engage), Reason.BEGIN);
            }
            case Posture.Fighting fighting -> {
                if (spotted == null) {
                    yield new Transition<>(state, new UnitPlan.Fight(fighting.ship(), fighting.allocation()));
                }
                yield decide(state.withOrder(new Order.Attack(spotted, new Seat(fighting.ship(),
                        fighting.allocation()))), Reason.BEGIN);
            }
            case Posture.Sitting sitting -> new Transition<>(state, new UnitPlan.Sit(sitting.ship(),
                    sitting.allocation()));
        };
    }

    private Step<Order> order(Order order, Reason reason) {
        return switch (order) {
            case Order.Walk walk -> march(walk.target(), walk.aggressive(), walk.attempts(), walk.engaged(), reason,
                    (attempts, engaged) -> new Order.Walk(walk.target(), walk.aggressive(), attempts, engaged));
            case Order.Defend defend -> march(defend.target(), true, defend.attempts(), defend.engaged(), reason,
                    (attempts, engaged) -> new Order.Defend(defend.target(), attempts, engaged));
            case Order.Hunt hunt -> hunt(hunt);
            case Order.Attack attack -> attack(attack);
            case Order.Gather gather -> gather(gather, reason);
            case Order.Repair repair -> repair(repair, reason);
            case Order.Enter enter -> enter(enter, reason);
            case Order.Build build -> build(build);
            case Order.Transfer transfer -> transfer(transfer, reason);
        };
    }

    private static <O> Step<O> run(O next, UnitPlan plan) {
        return new Step.Continue<>(next, plan);
    }

    private static <O> Step<O> done() {
        return new Step.Done<>();
    }

    private Step<Order> march(Target target, boolean aggressive, int attempts, Order.@Nullable Hunt engaged,
            Reason reason, Marching marching) {
        if (reason instanceof Reason.Spotted spotted) {
            // Breaking off to hunt restores the walk's budget, so it carries on once the hunt ends.
            return march(target, aggressive, 0, new Order.Hunt(spotted.target()), Reason.BEGIN, marching);
        }
        if (engaged != null && hunt(engaged) instanceof Step.Continue<Order>(Order.Hunt hunt, UnitPlan plan)) {
            return run(marching.rebuild(attempts, hunt), plan);
        }
        if (attempts >= MAX_ATTEMPTS) {
            return done();
        }
        return run(marching.rebuild(attempts + 1, null), new UnitPlan.Walk(target, 0f, aggressive));
    }

    private Step<Order> hunt(Order.Hunt hunt) {
        Selectable<?> target = hunt.target();
        if (view.isDead(target)) {
            return done();
        }
        float range = view.range(target);
        if (view.isCloseEnough(range, target)) {
            return run(new Order.Hunt(target, 0), new UnitPlan.Attack(target, null));
        }
        if (hunt.attempts() >= MAX_ATTEMPTS) {
            return done();
        }
        return run(new Order.Hunt(target, hunt.attempts() + 1), new UnitPlan.Walk(target, range, false));
    }

    private Step<Order> attack(Order.Attack attack) {
        if (view.isDead(attack.target()) || !view.canReach(attack.target())) {
            return done();
        }
        return run(attack, new UnitPlan.Attack(attack.target(), attack.seat()));
    }

    private Step<Order> gather(Order.Gather gather, Reason reason) {
        switch (gather.phase()) {
            case Order.Gather.Harvesting harvesting -> {
                if (harvest(harvesting.harvest(),
                        reason) instanceof Step.Continue<Order.Harvest>(Order.Harvest harvest, UnitPlan plan)) {
                    return run(in(gather, new Order.Gather.Harvesting(harvest, harvesting.attempts())), plan);
                }
                gather = in(gather, new Order.Gather.Approaching(harvesting.attempts()));
            }
            case Order.Gather.Depositing depositing -> {
                gather = in(gather, new Order.Gather.Delivering(depositing.attempts()));
                if (reason instanceof Reason.Performed && view.carriesAnything()) {
                    // The store is full: move into it rather than carry the rest around.
                    return enter(new Order.Enter(depositing.store()), Reason.BEGIN);
                }
            }
            case Order.Gather.Delivering ignored -> {
                if (reason instanceof Reason.Found found) {
                    Building building = found.occupant() instanceof Building b ? b : null;
                    gather = new Order.Gather(gather.supplyType(), gather.supply(), gather.assignedBuilding(),
                            building, gather.phase());
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

    private Step<Order> collect(Order.Gather gather, int attempts) {
        Supply supply = gather.supply();
        if (supply != null && view.isDead(supply)) {
            supply = null;
            attempts = 0;
        }
        if (supply != null && view.isCloseEnough(view.range(supply), supply)) {
            return gather(collecting(gather, supply, new Order.Gather.Harvesting(new Order.Harvest(supply,
                    gather.supplyType(), 0), attempts)), Reason.BEGIN);
        }
        if (attempts >= MAX_ATTEMPTS) {
            return transfer(new Order.Transfer(), Reason.BEGIN);
        }
        if (supply == null) {
            return gather(collecting(gather, null, new Order.Gather.Harvesting(new Order.Harvest(null,
                    gather.supplyType(), 0), attempts + 1)), Reason.BEGIN);
        }
        return run(collecting(gather, supply, new Order.Gather.Approaching(attempts + 1)), new UnitPlan.Walk(supply,
                0f, false));
    }

    private Step<Order> dropoff(Order.Gather gather, int attempts) {
        Building assigned = gather.assignedBuilding();
        boolean assignedStanding = assigned != null && !view.isDead(assigned);
        Building found = gather.foundBuilding();
        Building store = assignedStanding ? assigned.getEntrance() : found != null ? found.getEntrance() : null;
        if (store != null && view.isCloseEnough(0f, store)) {
            return run(in(gather, new Order.Gather.Depositing(store, attempts)), new UnitPlan.Deposit(store));
        }
        if (attempts >= MAX_ATTEMPTS) {
            return done();
        }
        Order.Gather next = in(gather, new Order.Gather.Delivering(attempts + 1));
        if (assignedStanding) {
            return run(next, new UnitPlan.Walk(assigned.getEntrance(), 0f, false));
        }
        return run(next, new UnitPlan.FindStore());
    }

    private Order.Gather collecting(Order.Gather gather, @Nullable Supply supply, Order.Gather.Phase phase) {
        return new Order.Gather(gather.supplyType(), supply, gather.assignedBuilding(), gather.foundBuilding(),
                phase);
    }

    private Order.Gather in(Order.Gather gather, Order.Gather.Phase phase) {
        return collecting(gather, gather.supply(), phase);
    }

    private Step<Order.Harvest> harvest(Order.Harvest harvest, Reason reason) {
        if (view.isFull(harvest.supplyType())) {
            return done();
        }
        Supply supply = harvest.supply();
        if (reason instanceof Reason.Found found) {
            supply = found.occupant() instanceof Supply s ? s : null;
        }
        if (supply != null && !view.isEmpty(supply) && view.isCloseEnough(0f, supply)) {
            return run(new Order.Harvest(supply, harvest.supplyType(), 0), new UnitPlan.Harvest(supply));
        }
        if (harvest.attempts() >= MAX_ATTEMPTS) {
            return done();
        }
        return run(new Order.Harvest(supply, harvest.supplyType(), harvest.attempts() + 1), new UnitPlan.FindSupply(
                harvest.supplyType()));
    }

    private Step<Order> repair(Order.Repair repair, Reason reason) {
        Building building = repair.building();
        int fetchAttempts = 0;
        int mendAttempts = 0;
        switch (repair.phase()) {
            case Order.Repair.Fetching fetching -> {
                if (harvest(fetching.harvest(),
                        reason) instanceof Step.Continue<Order.Harvest>(Order.Harvest harvest, UnitPlan plan)) {
                    return run(new Order.Repair(building, new Order.Repair.Fetching(harvest, fetching.attempts())),
                            plan);
                }
                fetchAttempts = fetching.attempts();
            }
            case Order.Repair.Mending mending -> mendAttempts = mending.attempts();
        }
        if (view.isDead(building)) {
            return done();
        }
        if (view.carries(TreeSupply.class)) {
            if (view.isCloseEnough(0f, building)) {
                if (view.isDamaged(building)) {
                    return run(new Order.Repair(building, new Order.Repair.Mending(mendAttempts)),
                            new UnitPlan.Repair(building));
                }
                if (view.storesSupplies(building) && view.sharesOwner(building)) {
                    return enter(new Order.Enter(building), Reason.BEGIN);
                }
                return done();
            }
            if (mendAttempts >= MAX_ATTEMPTS) {
                return done();
            }
            return run(new Order.Repair(building, new Order.Repair.Mending(mendAttempts + 1)), new UnitPlan.Walk(
                    building, 0f, false));
        }
        if (fetchAttempts >= MAX_ATTEMPTS) {
            return done();
        }
        return repair(new Order.Repair(building, new Order.Repair.Fetching(new Order.Harvest(null, TreeSupply.class,
                0), fetchAttempts + 1)), Reason.BEGIN);
    }

    private Step<Order> enter(Order.Enter enter, Reason reason) {
        Building building = enter.building();
        if (reason instanceof Reason.Performed || view.isDead(building)) {
            return done();
        }
        if (view.isCloseEnough(0f, building)) {
            return view.canEnter(building) ? run(enter, new UnitPlan.Enter(building, true)) : done();
        }
        if (enter.attempts() >= MAX_ATTEMPTS) {
            return done();
        }
        return run(new Order.Enter(building, enter.attempts() + 1), new UnitPlan.Walk(building, 0f, false));
    }

    private Step<Order> build(Order.Build build) {
        Building building = build.building();
        if (view.isDead(building)) {
            return done();
        }
        if (view.isPlaced(building)) {
            return view.isDamaged(building) ? repair(new Order.Repair(building), Reason.BEGIN) : done();
        }
        float distance = view.placingDistance(building);
        if (view.isCloseEnough(distance, building)) {
            // Once placed, the site is built by repairing it.
            return view.isPlacingLegal(building) ? run(new Order.Repair(building), new UnitPlan.Place(
                    building)) : done();
        }
        if (build.attempts() >= MAX_ATTEMPTS) {
            return done();
        }
        return run(new Order.Build(building, build.attempts() + 1), new UnitPlan.Walk(building, distance, false));
    }

    private Step<Order> transfer(Order.Transfer transfer, Reason reason) {
        if (reason instanceof Reason.Performed) {
            return done();
        }
        Building found = transfer.found();
        if (reason instanceof Reason.Found f) {
            found = f.occupant() instanceof Building b ? b : null;
        }
        if (found != null && view.isCloseEnough(0f, found)) {
            return view.canEnter(found) ? run(new Order.Transfer(found, transfer.attempts()), new UnitPlan.Enter(found,
                    false)) : done();
        }
        if (transfer.attempts() >= MAX_ATTEMPTS) {
            return done();
        }
        return run(new Order.Transfer(found, transfer.attempts() + 1), new UnitPlan.FindStore());
    }
}
