package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Runs a ship's voyages: the imperative shell around {@link ShipReducer}. Every {@link ShipEvent} is
 * reduced into the ship's state, and the {@link ShipCommand}s that come back are carried out on the
 * running activity or on the ship.
 */
@NullMarked
public final class ShipController implements TaskRunner, StateController<ShipState, ShipEvent> {
    private final Ship ship;
    private final ReducerLoop<ShipState, ShipEvent, List<ShipCommand>> loop;
    private @Nullable Activity running;

    public ShipController(Ship ship) {
        this.ship = ship;
        this.loop = new ReducerLoop<>(new ShipReducer(ship::slid), new ShipState(new Task.Inert()),
                this::executeAll);
    }

    /** Takes the first decision; called once the ship is far enough constructed to act. */
    public void start() {
        dispatch(new ShipEvent.Started());
    }

    public void sail(Target target) {
        dispatch(new ShipEvent.Sailed(target));
    }

    public void dock() {
        dispatch(new ShipEvent.Docked());
    }

    public boolean isSailing() {
        return loop.state().task() instanceof Voyage;
    }

    @Override
    public ShipState getState() {
        return loop.state();
    }

    @Override
    public Task getPrimaryTask() {
        return loop.state().task();
    }

    @Override
    public @Nullable Activity getActivity() {
        return running;
    }

    @Override
    public void dispatch(ShipEvent event) {
        loop.dispatch(event);
    }

    @Override
    public void animate(float t) {
        Activity activity = running;
        if (activity == null) {
            return;
        }
        loop.report(() -> activity.animate(t) == Activity.State.DONE ? new ShipEvent.Report.Finished(
                activity.outcome()) : null);
    }

    private void executeAll(List<ShipCommand> commands) {
        for (ShipCommand command : commands) {
            execute(command);
        }
    }

    private void execute(ShipCommand command) {
        switch (command) {
            case ShipCommand.Cancel ignored -> {
                Activity activity = running;
                assert activity != null : "cancelling with nothing running";
                activity.cancel();
                running = null;
            }
            case ShipCommand.Release ignored -> running = null;
            case ShipCommand.Start start -> running = create(start.run());
            case ShipCommand.Perform perform -> {
                switch (perform.operation()) {
                    case FINISH_SLIDE -> ship.endSlide();
                    case END_TRIP -> ship.endTrip();
                }
                loop.dispatch(new ShipEvent.Report.Performed());
            }
        }
    }

    private Activity create(ShipPlan.Run run) {
        return switch (run) {
            case ShipPlan.Moor ignored -> new NullActivity();
            case ShipPlan.Slide ignored -> new ShipSlideActivity(ship);
            case ShipPlan.Sail sail -> new SailActivity(ship, sail.target());
            case ShipPlan.Unstuck ignored -> new ShipUnstuckActivity(ship);
        };
    }
}
