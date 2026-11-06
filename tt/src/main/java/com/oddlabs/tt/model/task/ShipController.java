package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Runs a ship's voyages. A new destination or the ship's destruction takes effect at once; otherwise
 * the ship decides again whenever a leg of its voyage ends.
 */
@NullMarked
public final class ShipController implements TaskRunner, StateController<Task, ShipEvent> {
    private final Ship ship;
    private Task state = new Task.Inert();
    private @Nullable Activity running;

    public ShipController(Ship ship) {
        this.ship = ship;
    }

    /** Takes the first decision; called once the ship is far enough constructed to act. */
    public void start() {
        decide(Reason.BEGIN);
    }

    public void sail(Target target) {
        dispatch(new ShipEvent.Sailed(target));
    }

    public void dock() {
        dispatch(new ShipEvent.Docked());
    }

    public boolean isSailing() {
        return state instanceof Voyage;
    }

    @Override
    public Task getState() {
        return state;
    }

    @Override
    public Task getPrimaryTask() {
        return state;
    }

    @Override
    public @Nullable Activity getActivity() {
        return running;
    }

    @Override
    public void dispatch(ShipEvent event) {
        state = switch (event) {
            case ShipEvent.Sailed sailed -> new Voyage(sailed.target());
            case ShipEvent.Docked ignored -> new Task.Inert();
        };
        Activity activity = running;
        running = null;
        if (activity != null) {
            activity.cancel();
        }
        decide(Reason.BEGIN);
    }

    @Override
    public void animate(float t) {
        Activity activity = running;
        if (activity == null) {
            return;
        }
        if (activity.animate(t) == Activity.State.DONE && running == activity) {
            running = null;
            decide(activity.outcome());
        }
    }

    private void decide(Reason reason) {
        while (true) {
            ShipReducer.Decision decision = ShipReducer.decide(state, reason, ship.slid());
            state = decision.state();
            switch (decision.plan()) {
                case ShipReducer.Run run -> {
                    running = create(run);
                    return;
                }
                case ShipReducer.Operation operation -> {
                    switch (operation) {
                        case FINISH_SLIDE -> ship.endSlide();
                        case END_TRIP -> ship.endTrip();
                    }
                    reason = Reason.PERFORMED;
                }
            }
        }
    }

    private Activity create(ShipReducer.Run run) {
        return switch (run) {
            case ShipReducer.Moor ignored -> new NullActivity();
            case ShipReducer.Slide ignored -> new ShipSlideActivity(ship);
            case ShipReducer.Sail sail -> new SailActivity(ship, sail.target());
            case ShipReducer.Unstuck ignored -> new ShipUnstuckActivity(ship);
        };
    }
}
