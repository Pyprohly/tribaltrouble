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
public final class ShipController implements Controller {
    private final Ship ship;
    private Task state = new Task.Inert();
    private @Nullable Activity running;

    public ShipController(Ship ship) {
        this.ship = ship;
    }

    /** Takes the first decision; called once the ship is far enough constructed to act. */
    public void start() {
        decide(Event.BEGIN);
    }

    public void sail(Target target) {
        change(new Voyage(target));
    }

    public void dock() {
        change(new Task.Inert());
    }

    public boolean isSailing() {
        return state instanceof Voyage;
    }

    @Override
    public Task getPrimaryTask() {
        return state;
    }

    @Override
    public @Nullable Activity getActivity() {
        return running;
    }

    private void change(Task next) {
        state = next;
        Activity activity = running;
        running = null;
        if (activity != null) {
            activity.cancel();
        }
        decide(Event.BEGIN);
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

    private void decide(Event event) {
        while (true) {
            ShipReducer.Decision decision = ShipReducer.decide(state, event, ship.slid());
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
                    event = Event.PERFORMED;
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
