package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A ship's state machine. {@link #reduce} applies an event to the ship's state and returns the
 * commands its controller carries out; it reads whether the ship has slid off the shore and changes
 * nothing itself.
 *
 * <p>The state says whether the ship is docked or on a voyage, and which leg of the voyage it is on.
 * A new destination or the ship's destruction takes effect at once; otherwise the ship decides again
 * whenever a leg of its voyage ends.
 */
@NullMarked
public final class ShipStateReducer implements StateReducer<ShipState, ShipEvent, List<ShipCommand>> {
    /** How many times a ship fails to work itself loose before it gives up the voyage. */
    private static final int MAX_FAILED_UNSTUCKS = 3;

    /** Whether the ship has already left the shore it was built on. */
    private final BooleanSupplier slid;

    public ShipStateReducer(BooleanSupplier slid) {
        this.slid = slid;
    }

    @Override
    public Transition<ShipState, List<ShipCommand>> reduce(ShipState state, ShipEvent event) {
        Task task = state.task();
        return switch (event) {
            case ShipEvent.Started _ -> {
                if (!(state.execution() instanceof ShipState.Execution.Unstarted)) {
                    assert false : "a ship started twice";
                    yield withoutCommands(state);
                }
                yield replan(state, task, ShipCommand.INTERRUPT, ShipReason.BEGIN);
            }
            case ShipEvent.Sailed sailed -> replan(
                    state,
                    new Voyage(sailed.target()),
                    ShipCommand.INTERRUPT,
                    ShipReason.BEGIN
            );
            case ShipEvent.Destroyed _ -> replan(state, new Task.Inert(), ShipCommand.INTERRUPT, ShipReason.BEGIN);
            case ShipEvent.Report.Finished finished -> {
                if (!(state.execution() instanceof ShipState.Execution.Running)) {
                    yield withoutCommands(state);
                }
                yield replan(state, task, ShipCommand.RELEASE, finished.outcome());
            }
            case ShipEvent.Report.Performed performed -> {
                if (
                    !(state.execution() instanceof ShipState.Execution.Performing performing)
                            || performing.operation() != performed.operation()
                ) {
                    yield withoutCommands(state);
                }
                yield replan(state, task, ShipCommand.INTERRUPT, ShipReason.PERFORMED);
            }
        };
    }

    private static Transition<ShipState, List<ShipCommand>> withoutCommands(ShipState state) {
        return new Transition<>(state, List.of());
    }

    /** Stops what runs with the stop command, plans for the next task, and starts or performs what it asks for. */
    private Transition<ShipState, List<ShipCommand>> replan(
            ShipState state,
            Task next,
            ShipCommand.Stop stop,
            ShipReason reason
    ) {
        List<ShipCommand> commands = new ArrayList<>(2);
        if (state.execution() instanceof ShipState.Execution.Running) {
            commands.add(stop);
        }
        Step<Task, ShipPlan> decision = decide(next, reason);
        ShipState.Execution execution = switch (decision.plan()) {
            case ShipPlan.Run run -> {
                commands.add(new ShipCommand.Run(run));
                yield ShipState.Execution.RUNNING;
            }
            case ShipPlan.Operation operation -> {
                commands.add(new ShipCommand.Perform(operation));
                yield new ShipState.Execution.Performing(operation);
            }
        };
        return new Transition<>(new ShipState(decision.next(), execution), commands);
    }

    /**
     * Lets the voyage decide its next leg, or moors a ship that has no voyage or ends the one it
     * has.
     */
    private Step<Task, ShipPlan> decide(Task task, ShipReason reason) {
        if (!(task instanceof Voyage voyage)) {
            return new Step<>(new Task.Inert(), new ShipPlan.Moor());
        }
        if (reason instanceof ShipReason.Begin) {
            Voyage.Phase phase = slid.getAsBoolean() ? Voyage.Phase.SAILING : Voyage.Phase.SLIDING;
            return leg(voyage.withPhase(phase));
        }
        return switch (voyage.phase()) {
            case SLIDING -> {
                if (reason instanceof ShipReason.Performed) {
                    yield leg(voyage.withPhase(Voyage.Phase.SAILING));
                }
                yield new Step<>(voyage, new ShipPlan.FinishSlide());
            }
            case SAILING -> {
                if (reason instanceof ShipReason.Blocked) {
                    yield leg(voyage.withPhase(Voyage.Phase.UNSTICKING));
                }
                yield new Step<>(new Task.Inert(), new ShipPlan.EndTrip());
            }
            case UNSTICKING -> {
                if (reason instanceof ShipReason.Completed) {
                    yield leg(voyage.withPhase(Voyage.Phase.SAILING).withFailedUnstucks(0));
                }
                int failed = voyage.failedUnstucks() + 1;
                if (failed >= MAX_FAILED_UNSTUCKS) {
                    yield new Step<>(new Task.Inert(), new ShipPlan.Moor());
                }
                yield leg(voyage.withPhase(Voyage.Phase.SAILING).withFailedUnstucks(failed));
            }
        };
    }

    /** Starts the leg the voyage is on. */
    private static Step<Task, ShipPlan> leg(Voyage voyage) {
        return new Step<>(voyage, switch (voyage.phase()) {
            case SLIDING -> new ShipPlan.Slide();
            case SAILING -> new ShipPlan.Sail(voyage.target());
            case UNSTICKING -> new ShipPlan.Unstuck();
        });
    }
}
