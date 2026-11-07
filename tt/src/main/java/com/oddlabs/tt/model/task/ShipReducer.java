package com.oddlabs.tt.model.task;

import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;

/**
 * A ship's state machine: whether it is docked or on a voyage, and which leg of the voyage it is on.
 * A new destination or the ship's destruction takes effect at once; otherwise the ship decides again
 * whenever a leg of its voyage ends.
 */
@NullMarked
public final class ShipReducer implements StateReducer<ShipState, ShipEvent, List<ShipCommand>> {
    /** How many times a ship fails to work itself loose before it gives up the voyage. */
    private static final int MAX_FAILED_UNSTUCKS = 3;

    private final ShipView view;

    public ShipReducer(ShipView view) {
        this.view = view;
    }

    @Override
    public Transition<ShipState, List<ShipCommand>> reduce(ShipState state, ShipEvent event) {
        return switch (event) {
            case ShipEvent.Started ignored -> state.slot() == ShipState.Slot.VACANT ? replan(state,
                    ShipCommand.CANCEL, state.task(), Reason.BEGIN) : unchanged(state);
            case ShipEvent.Sailed sailed -> replan(state, ShipCommand.CANCEL, new Voyage(sailed.target()),
                    Reason.BEGIN);
            case ShipEvent.Docked ignored -> replan(state, ShipCommand.CANCEL, new Task.Inert(), Reason.BEGIN);
            case ShipEvent.Report.Finished finished -> state.slot() == ShipState.Slot.RUNNING ? replan(state,
                    ShipCommand.RELEASE, state.task(), finished.outcome()) : unchanged(state);
            case ShipEvent.Report.Performed ignored -> state.slot() == ShipState.Slot.PERFORMING ? replan(state,
                    ShipCommand.CANCEL, state.task(), Reason.PERFORMED) : unchanged(state);
        };
    }

    private static Transition<ShipState, List<ShipCommand>> unchanged(ShipState state) {
        return new Transition<>(state, List.of());
    }

    /** Stops what runs with the stop command, plans for the task, and starts or performs what it asks for. */
    private Transition<ShipState, List<ShipCommand>> replan(ShipState state, ShipCommand stop, Task task,
            ShipReason reason) {
        List<ShipCommand> commands = new ArrayList<>(2);
        if (state.slot() == ShipState.Slot.RUNNING) {
            commands.add(stop);
        }
        Decision<Task, ShipPlan> decision = plan(task, reason);
        ShipState.Slot slot = switch (decision.plan()) {
            case ShipPlan.Run run -> {
                commands.add(new ShipCommand.Start(run));
                yield ShipState.Slot.RUNNING;
            }
            case ShipPlan.Operation operation -> {
                commands.add(new ShipCommand.Perform(operation));
                yield ShipState.Slot.PERFORMING;
            }
        };
        return new Transition<>(new ShipState(decision.state(), slot), commands);
    }

    private Decision<Task, ShipPlan> plan(Task state, ShipReason reason) {
        if (!(state instanceof Voyage voyage)) {
            return new Decision<>(new Task.Inert(), new ShipPlan.Moor());
        }
        if (reason instanceof Reason.Begin) {
            return leg(new Voyage(voyage.target(), view.slid() ? Voyage.Phase.SAILING : Voyage.Phase.SLIDING,
                    voyage.failedUnstucks()));
        }
        return switch (voyage.phase()) {
            case SLIDING -> reason instanceof Reason.Performed ? leg(with(voyage, Voyage.Phase.SAILING,
                    voyage.failedUnstucks())) : new Decision<>(voyage, ShipPlan.Operation.FINISH_SLIDE);
            case SAILING -> reason instanceof ShipReason.Blocked ? leg(with(voyage, Voyage.Phase.UNSTICKING,
                    voyage.failedUnstucks())) : new Decision<>(new Task.Inert(), ShipPlan.Operation.END_TRIP);
            case UNSTICKING -> {
                if (reason instanceof Reason.Completed) {
                    yield leg(with(voyage, Voyage.Phase.SAILING, 0));
                }
                int failed = voyage.failedUnstucks() + 1;
                if (failed >= MAX_FAILED_UNSTUCKS) {
                    yield new Decision<>(new Task.Inert(), new ShipPlan.Moor());
                }
                yield leg(with(voyage, Voyage.Phase.SAILING, failed));
            }
        };
    }

    private static Voyage with(Voyage voyage, Voyage.Phase phase, int failedUnstucks) {
        return new Voyage(voyage.target(), phase, failedUnstucks);
    }

    private static Decision<Task, ShipPlan> leg(Voyage voyage) {
        return new Decision<>(voyage, switch (voyage.phase()) {
            case SLIDING -> new ShipPlan.Slide();
            case SAILING -> new ShipPlan.Sail(voyage.target());
            case UNSTICKING -> new ShipPlan.Unstuck();
        });
    }
}
