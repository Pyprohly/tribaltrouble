package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Runs a ship's voyages: the imperative shell around {@link ShipStateReducer}. Every {@link ShipEvent} is
 * reduced into the ship's state, and the {@link ShipCommand}s that come back are carried out on the
 * running activity or on the ship.
 */
@NullMarked
public final class ShipStateController implements TaskRunner {
    private final Ship ship;
    private final ReducerLoop<ShipState, ShipEvent, List<ShipCommand>> loop;
    private @Nullable ShipActivity runningActivity;

    public ShipStateController(Ship ship) {
        this.ship = ship;
        this.loop = new ReducerLoop<>(new ShipStateReducer(ship::slid), new ShipState(new Task.Inert()),
                this::executeAll);
    }

    /** Takes the first decision; called once the ship is far enough constructed to act. */
    public void start() {
        dispatch(new ShipEvent.Started());
    }

    public void sail(Target target) {
        dispatch(new ShipEvent.Sailed(target));
    }

    public void destroy() {
        dispatch(new ShipEvent.Destroyed());
    }

    public boolean isSailing() {
        return loop.state().task() instanceof Voyage;
    }

    public ShipState getState() {
        return loop.state();
    }

    @Override
    public Task getPrimaryTask() {
        return loop.state().task();
    }

    @Override
    public @Nullable Activity getRunningActivity() {
        return runningActivity;
    }

    @Override
    public @Nullable TransitionTrace<ShipState, ShipEvent, List<ShipCommand>> getTrace() {
        return loop.trace();
    }

    @Override
    public void animate(float t) {
        ShipActivity activity = runningActivity;
        if (activity == null) {
            return;
        }
        loop.report(() -> activity.animate(t) == Activity.State.DONE ? new ShipEvent.Report.Finished(
                activity.outcome()) : null);
    }

    private void dispatch(ShipEvent event) {
        loop.dispatch(event);
    }

    private void executeAll(List<ShipCommand> commands) {
        for (ShipCommand command : commands) {
            execute(command);
        }
    }

    private void execute(ShipCommand command) {
        switch (command) {
            case ShipCommand.Interrupt _ -> {
                assert runningActivity != null : "interrupting with nothing running";
                if (runningActivity != null) {
                    runningActivity.cancel();
                }
                runningActivity = null;
            }
            case ShipCommand.Release _ -> runningActivity = null;
            case ShipCommand.Run run -> runningActivity = create(run.plan());
            case ShipCommand.Perform perform -> {
                switch (perform.operation()) {
                    case ShipPlan.FinishSlide _ -> ship.endSlide();
                    case ShipPlan.EndTrip _ -> ship.endTrip();
                }
                dispatch(new ShipEvent.Report.Performed(perform.operation()));
            }
        }
    }

    private ShipActivity create(ShipPlan.Run run) {
        return switch (run) {
            case ShipPlan.Moor _ -> new NullActivity();
            case ShipPlan.Slide _ -> new ShipSlideActivity(ship);
            case ShipPlan.Sail sail -> new SailActivity(ship, sail.target());
            case ShipPlan.Unstuck _ -> new ShipUnstuckActivity(ship);
        };
    }
}
