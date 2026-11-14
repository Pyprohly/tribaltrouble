package com.oddlabs.tt.model.task;

import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.ShipAllocation;
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
 * <p>A decision is made by the current task, for a {@link UnitReason}. An order decides through its
 * own {@link OrderTaskReducer}, which hands a harvest held by the order to a {@link HarvestTaskReducer}. A
 * task that finishes hands over to the slot beneath it: an order to the posture, an interruption to
 * whatever it interrupted. A posture that spots an enemy gives itself an order. {@link #decide}
 * follows these handovers in one call until a task has something to do.
 */
@NullMarked
public final class UnitStateReducer implements StateReducer<UnitState, UnitEvent, List<UnitCommand>> {
    /**
     * How many times one decision may hand over between slots. A slot that takes over begins, and
     * only a posture that spotted an enemy gives itself an order, so a decision needs two at most:
     * from an interruption through the order to the posture, or from the posture to its own order
     * and back.
     */
    private static final int MAX_HANDOVERS = 2;

    private final UnitView view;
    private final OrderTaskReducer orderReducer;

    public UnitStateReducer(UnitView view) {
        this.view = view;
        this.orderReducer = new OrderTaskReducer(view);
    }

    @Override
    public Transition<UnitState, List<UnitCommand>> reduce(UnitState state, UnitEvent event) {
        // Nothing should reach a unit that has left the world; ignore it if something does.
        if (state.execution() instanceof UnitState.Execution.Gone) {
            assert false : "an event for a unit that has left the world";
            return withoutCommands(state);
        }
        TaskSlots tasks = state.tasks();
        // Nothing replaces dying, so a dying unit only follows its controller's reports.
        if (tasks.dying() != null && !(event instanceof UnitEvent.Report)) {
            return withoutCommands(state);
        }
        return switch (event) {
            case UnitEvent.Started _ -> {
                if (!(state.execution() instanceof UnitState.Execution.Unstarted)) {
                    assert false : "a unit started twice";
                    yield withoutCommands(state);
                }
                yield replan(state, tasks, UnitCommand.INTERRUPT, UnitReason.BEGIN);
            }
            case UnitEvent.Ordered ordered -> {
                // A new order lifts the excuse: from now on it waits for the activity only while
                // that is committed.
                if (state.execution() instanceof UnitState.Execution.Running running && running.leftToFinish()) {
                    state = state.withExecution(running.withLeftToFinish(false));
                }
                yield reconsiderWhenYielding(state, tasks.withOrder(ordered.order()));
            }
            case UnitEvent.Excused _ -> excuse(state);
            case UnitEvent.Stunned stunned -> reconsiderAtOnce(
                    state,
                    tasks.withStunned(new Interruption.Stunned(stunned.seconds()))
            );
            case UnitEvent.Cast cast -> {
                TaskSlots interrupted = cast.clearOrder() ? tasks.withOrder(null) : tasks;
                yield reconsiderAtOnce(state, interrupted.withCasting(new Interruption.Casting(cast.factory())));
            }
            case UnitEvent.Died _ -> reconsiderAtOnce(state, tasks.withOnlyDying());
            case UnitEvent.MountedTower _ -> reconsiderAtOnce(state, new TaskSlots(new Posture.Idle(false)));
            case UnitEvent.MountedShip mounted -> {
                Posture posture;
                if (mounted.allocation().getRole() == ShipAllocation.FIGHTING) {
                    posture = new Posture.Fighting(mounted.ship(), mounted.allocation());
                } else {
                    posture = new Posture.Sitting(mounted.ship(), mounted.allocation());
                }
                yield reconsiderAtOnce(state, new TaskSlots(posture));
            }
            case UnitEvent.Unmounted _ -> reconsiderAtOnce(state, new TaskSlots(new Posture.Idle(true)));
            case UnitEvent.Report.Progressed progressed -> {
                if (!(state.execution() instanceof UnitState.Execution.Running running)) {
                    yield withoutCommands(state);
                }
                yield reconsiderWhenYielding(state.withExecution(running.withProgress(progressed.progress())), tasks);
            }
            case UnitEvent.Report.Finished finished -> {
                if (!(state.execution() instanceof UnitState.Execution.Running running)) {
                    yield withoutCommands(state);
                }
                // A walk left to finish hunts what it spots directly, not via the posture, so no
                // walk back will follow.
                if (running.leftToFinish() && finished.outcome() instanceof UnitReason.Spotted spotted) {
                    yield replan(state, tasks.withOrder(new Order.Hunt(spotted.target())), UnitCommand.RELEASE,
                            UnitReason.BEGIN);
                }
                // A committed activity can outlive its task; the current task then begins instead.
                UnitReason reason = running.task() == tasks.current() ? finished.outcome() : UnitReason.BEGIN;
                yield replan(state, tasks, UnitCommand.RELEASE, reason);
            }
            // Events the operation raised are reduced first and may have moved the unit on, even
            // to another one.
            case UnitEvent.Report.Performed performed -> {
                if (
                    !(state.execution() instanceof UnitState.Execution.Performing performing)
                            || performing.operation() != performed.operation()
                ) {
                    yield withoutCommands(state);
                }
                yield replan(state, tasks, UnitCommand.INTERRUPT, UnitReason.PERFORMED);
            }
            case UnitEvent.Report.Removed _ -> {
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
        if (state.execution() instanceof UnitState.Execution.Running running) {
            boolean sameTask = running.task() == next.current();
            if (sameTask) {
                return withoutCommands(state.withTasks(next));
            }
        }
        return replan(state, next, UnitCommand.INTERRUPT, UnitReason.BEGIN);
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
        return replan(state, next, UnitCommand.INTERRUPT, UnitReason.BEGIN);
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
            UnitReason reason
    ) {
        List<UnitCommand> commands = new ArrayList<>(3);
        if (state.execution() instanceof UnitState.Execution.Running) {
            commands.add(stop);
        }
        if (next.posture() != state.tasks().posture()) {
            commands.add(UnitCommand.DISCARD);
        }
        Step<TaskSlots, UnitPlan> decision = decide(next, reason);
        TaskSlots tasks = decision.next();
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
    private Step<TaskSlots, UnitPlan> decide(TaskSlots tasks, UnitReason reason) {
        for (int handovers = 0;; handovers++) {
            assert handovers <= MAX_HANDOVERS : "a decision that never ends in something to do";
            Interruption interruption = tasks.interruption();
            Order order = tasks.order();
            if (interruption != null) {
                UnitPlan plan = decideInterruption(interruption, reason);
                if (plan != null) {
                    return new Step<>(tasks, plan);
                }
                tasks = tasks.withoutInterruption();
            } else if (order != null) {
                if (orderReducer.decide(order, reason) instanceof Step(Order next, UnitPlan plan)) {
                    return new Step<>(tasks.withOrder(next), plan);
                }
                tasks = tasks.withOrder(null);
            } else {
                Order engage = null;
                if (reason instanceof UnitReason.Spotted spotted) {
                    engage = engage(tasks.posture(), spotted.target());
                }
                if (engage == null) {
                    return new Step<>(tasks, decidePosture(tasks.posture()));
                }
                tasks = tasks.withOrder(engage);
            }
            reason = UnitReason.BEGIN;
        }
    }

    private static @Nullable UnitPlan decideInterruption(Interruption interruption, UnitReason reason) {
        // A stun or spell decides only as it begins; any later decision means it has ended.
        if (!(interruption instanceof Interruption.Dying) && !(reason instanceof UnitReason.Begin)) {
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
}
