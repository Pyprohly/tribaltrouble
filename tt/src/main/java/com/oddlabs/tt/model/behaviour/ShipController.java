package com.oddlabs.tt.model.behaviour;

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
    private @Nullable Behaviour running;

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
    public @Nullable Behaviour getBehaviour() {
        return running;
    }

    private void change(Task next) {
        state = next;
        Behaviour behaviour = running;
        running = null;
        if (behaviour != null) {
            behaviour.forceInterrupted();
        }
        decide(Event.BEGIN);
    }

    @Override
    public void animate(float t) {
        Behaviour behaviour = running;
        if (behaviour == null) {
            return;
        }
        if (behaviour.animate(t) == Behaviour.State.DONE && running == behaviour) {
            running = null;
            decide(behaviour.outcome());
        }
    }

    private void decide(Event event) {
        while (true) {
            ShipReducer.Decision decision = ShipReducer.decide(state, event, ship.slid());
            state = decision.state();
            switch (decision.plan()) {
                case ShipReducer.Activity activity -> {
                    running = create(activity);
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

    private Behaviour create(ShipReducer.Activity activity) {
        return switch (activity) {
            case ShipReducer.Moor ignored -> new NullBehaviour();
            case ShipReducer.Slide ignored -> new ShipSlideBehaviour(ship);
            case ShipReducer.Sail sail -> new SailBehaviour(ship, sail.target());
            case ShipReducer.Unstuck ignored -> new ShipUnstuckBehaviour(ship);
        };
    }
}
