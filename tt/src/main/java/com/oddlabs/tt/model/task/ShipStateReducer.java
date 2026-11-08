package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A ship's state machine: whether it is docked or on a voyage, and which leg of the voyage it is on.
 * A new destination or the ship's destruction takes effect at once; otherwise the ship decides again
 * whenever a leg of its voyage ends.
 */
@NullMarked
public final class ShipStateReducer implements StateReducer<ShipState, ShipEvent, List<ShipCommand>> {
    /** How many times a ship fails to work itself loose before it gives up the voyage. */
    private static final int MAX_FAILED_UNSTUCKS = 3;

    /** The task a decision leaves, and the plan {@link #replan} turns into commands. */
    private record Decision(Task task, ShipPlan plan) {
    }

    /** Whether the ship has already left the shore it was built on. */
    private final BooleanSupplier slid;

    public ShipStateReducer(BooleanSupplier slid) {
        this.slid = slid;
    }

    @Override
    public Transition<ShipState, List<ShipCommand>> reduce(ShipState state, ShipEvent event) {
        return switch (event) {
            case ShipEvent.Started _ -> state.execution() == ShipState.Execution.UNSTARTED ? replan(state,
                    ShipCommand.INTERRUPT, state.task(), Reason.BEGIN) : unchanged(state);
            case ShipEvent.Sailed sailed -> replan(state, ShipCommand.INTERRUPT, new Voyage(sailed.target()),
                    Reason.BEGIN);
            case ShipEvent.Docked _ -> replan(state, ShipCommand.INTERRUPT, new Task.Inert(), Reason.BEGIN);
            case ShipEvent.Report.Finished finished -> state.execution() == ShipState.Execution.RUNNING ? replan(state,
                    ShipCommand.RELEASE, state.task(), finished.outcome()) : unchanged(state);
            case ShipEvent.Report.Performed _ -> state.execution() == ShipState.Execution.PERFORMING ? replan(
                    state,
                    ShipCommand.INTERRUPT, state.task(), Reason.PERFORMED) : unchanged(state);
        };
    }

    private static Transition<ShipState, List<ShipCommand>> unchanged(ShipState state) {
        return new Transition<>(state, List.of());
    }

    /** Stops what runs with the stop command, plans for the task, and starts or performs what it asks for. */
    private Transition<ShipState, List<ShipCommand>> replan(ShipState state, ShipCommand.Stop stop, Task task,
            Reason reason) {
        List<ShipCommand> commands = new ArrayList<>(2);
        if (state.execution() == ShipState.Execution.RUNNING) {
            commands.add(stop);
        }
        Decision decision = decide(task, reason);
        ShipState.Execution execution = switch (decision.plan()) {
            case ShipPlan.Run run -> {
                commands.add(new ShipCommand.Run(run));
                yield ShipState.Execution.RUNNING;
            }
            case ShipPlan.Operation operation -> {
                commands.add(new ShipCommand.Perform(operation));
                yield ShipState.Execution.PERFORMING;
            }
        };
        return new Transition<>(new ShipState(decision.task(), execution), commands);
    }

    private Decision decide(Task state, Reason reason) {
        if (!(state instanceof Voyage voyage)) {
            return new Decision(new Task.Inert(), new ShipPlan.Moor());
        }
        if (reason instanceof Reason.Begin) {
            return leg(new Voyage(voyage.target(), slid.getAsBoolean() ? Voyage.Phase.SAILING : Voyage.Phase.SLIDING,
                    voyage.failedUnstucks()));
        }
        return switch (voyage.phase()) {
            case SLIDING -> reason instanceof Reason.Performed ? leg(withPhase(voyage, Voyage.Phase.SAILING,
                    voyage.failedUnstucks())) : new Decision(voyage, ShipPlan.Operation.FINISH_SLIDE);
            case SAILING -> reason instanceof Reason.Blocked ? leg(withPhase(voyage, Voyage.Phase.UNSTICKING,
                    voyage.failedUnstucks())) : new Decision(new Task.Inert(), ShipPlan.Operation.END_TRIP);
            case UNSTICKING -> {
                if (reason instanceof Reason.Completed) {
                    yield leg(withPhase(voyage, Voyage.Phase.SAILING, 0));
                }
                int failed = voyage.failedUnstucks() + 1;
                if (failed >= MAX_FAILED_UNSTUCKS) {
                    yield new Decision(new Task.Inert(), new ShipPlan.Moor());
                }
                yield leg(withPhase(voyage, Voyage.Phase.SAILING, failed));
            }
        };
    }

    private static Voyage withPhase(Voyage voyage, Voyage.Phase phase, int failedUnstucks) {
        return new Voyage(voyage.target(), phase, failedUnstucks);
    }

    private static Decision leg(Voyage voyage) {
        return new Decision(voyage, switch (voyage.phase()) {
            case SLIDING -> new ShipPlan.Slide();
            case SAILING -> new ShipPlan.Sail(voyage.target());
            case UNSTICKING -> new ShipPlan.Unstuck();
        });
    }
}
