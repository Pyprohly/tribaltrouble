package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingFinder;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipAllocation;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.SupplyFinder;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.UnitContainer;
import com.oddlabs.tt.model.UnitSupplyContainer;
import com.oddlabs.tt.model.weapon.MagicFactory;
import com.oddlabs.tt.model.weapon.ThrowingFactory;
import com.oddlabs.tt.model.weapon.ThrowingWeapon;
import com.oddlabs.tt.pathfinder.FinderTrackerAlgorithm;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Runs a unit's tasks. Inputs change the {@link UnitState}; {@link UnitReducer} decides what the
 * current task does; this class turns the answer into an activity or a world change.
 *
 * <p>One rule governs when an activity is replaced: a decision is taken as soon as the running
 * activity is interruptible, and waits for that otherwise. Urgent inputs (stuns, spells, death,
 * mounting) cancel the running activity and decide at once. A posture's activity is never
 * cancelled while the posture lasts: it is parked under the order or interruption above it and
 * resumed afterwards, keeping its timers and boarding progress.
 */
@NullMarked
public final class UnitController implements Controller {
    private final Unit unit;
    private final UnitView view;
    private UnitState state = new UnitState(new Posture.Idle(true));

    private @Nullable Activity running;
    /** The task the running activity was started for. */
    private @Nullable Task runningTask;
    private Activity.State progress = Activity.State.INTERRUPTIBLE;
    private boolean pending;

    private @Nullable Activity parked;
    private @Nullable Posture parkedPosture;

    /** Counts inputs, so a decision can tell that an operation it performed changed the task under it. */
    private int inputs;

    public UnitController(Unit unit) {
        this.unit = unit;
        this.view = new LiveView(unit);
    }

    /** Takes the first decision; called once the unit is far enough constructed to act. */
    public void start() {
        decide(Event.BEGIN);
    }

    public UnitState getState() {
        return state;
    }

    @Override
    public Task getPrimaryTask() {
        return state.primary();
    }

    @Override
    public @Nullable Activity getActivity() {
        return running;
    }

    public void order(@Nullable Order order) {
        change(state.withOrder(order), false);
    }

    public void stun(float seconds) {
        Interruption current = state.interruption();
        if (current instanceof Interruption.Dying) {
            return;
        }
        Interruption stun = new Interruption.Stunned(seconds);
        change(current instanceof Interruption.Stunned ? state.replacingInterruption(stun) : state.interruptedBy(stun),
                true);
    }

    /** Casts a spell; a stunned unit casts it once the stun wears off. */
    public void cast(MagicFactory factory, boolean clearOrder) {
        Interruption current = state.interruption();
        if (current instanceof Interruption.Dying) {
            return;
        }
        UnitState next = clearOrder ? state.withOrder(null) : state;
        Interruption casting = new Interruption.Casting(factory);
        if (current instanceof Interruption.Stunned) {
            change(next.followedBy(casting), false);
        } else {
            change(next.interruptedBy(casting), true);
        }
    }

    public void die() {
        change(new UnitState(state.posture(), null, List.of(new Interruption.Dying())), true);
    }

    public void mountTower() {
        change(new UnitState(new Posture.Idle(false)), true);
    }

    public void mountShip(Ship ship, ShipAllocation allocation) {
        Posture posture = allocation.getRole() == ShipAllocation.FIGHTING ? new Posture.Fighting(ship,
                allocation) : new Posture.Sitting(ship, allocation);
        change(new UnitState(posture), true);
    }

    public void unmount() {
        change(new UnitState(new Posture.Idle(true)), true);
    }

    private void change(UnitState next, boolean urgent) {
        state = next;
        inputs++;
        if (urgent) {
            stop();
            decide(Event.BEGIN);
            return;
        }
        // When only a task beneath the running one changed, it takes effect once it becomes current.
        boolean currentChanged = running == null || state.current() != runningTask;
        if (!currentChanged) {
            return;
        }
        if (running == null || progress != Activity.State.UNINTERRUPTIBLE) {
            decide(Event.BEGIN);
        } else {
            pending = true;
        }
    }

    @Override
    public void animate(float t) {
        Activity activity = running;
        if (activity == null) {
            return;
        }
        Activity.State result = activity.animate(t);
        if (running != activity) {
            return;
        }
        progress = result;
        if (result == Activity.State.DONE) {
            Event outcome = state.current() == runningTask ? activity.outcome() : Event.BEGIN;
            running = null;
            if (runningTask instanceof Posture posture && posture == state.posture()) {
                park(activity, posture);
            }
            decide(outcome);
        } else if (result == Activity.State.INTERRUPTIBLE && pending) {
            decide(Event.BEGIN);
        }
    }

    private void decide(Event event) {
        pending = false;
        while (true) {
            UnitReducer.Decision decision = UnitReducer.decide(state, event, view);
            state = decision.state();
            switch (decision.plan()) {
                case UnitPlan.Run run -> {
                    start(run);
                    return;
                }
                case UnitPlan.Operation operation -> {
                    stop();
                    int before = inputs;
                    perform(operation);
                    if (inputs != before || unit.isDead()) {
                        // The operation mounted or removed the unit, and that input has already decided.
                        return;
                    }
                    event = Event.PERFORMED;
                }
            }
        }
    }

    private void start(UnitPlan.Run run) {
        Task task = state.current();
        stop();
        Activity next = null;
        if (parkedPosture != state.posture() || task == parkedPosture) {
            next = task == parkedPosture ? parked : null;
            parked = null;
            parkedPosture = null;
        }
        if (next == null) {
            next = create(run);
        }
        running = next;
        runningTask = task;
        progress = next.initialState();
    }

    /** Stops the running activity, parking it if it belongs to the posture. */
    private void stop() {
        Activity activity = running;
        if (activity == null) {
            return;
        }
        running = null;
        if (runningTask instanceof Posture posture && posture == state.posture()) {
            park(activity, posture);
        } else {
            activity.cancel();
        }
    }

    private void park(Activity activity, Posture posture) {
        parked = activity;
        parkedPosture = posture;
    }

    private Activity create(UnitPlan.Run run) {
        return switch (run) {
            case UnitPlan.Watch watch -> new IdleActivity(unit, watch.canMove());
            case UnitPlan.Fight fight -> new ShipAttackActivity(unit, fight.ship(), fight.allocation());
            case UnitPlan.Sit sit -> new SittingActivity(unit, sit.ship(), sit.allocation());
            case UnitPlan.Walk walk -> new WalkActivity(unit, walk.target(), walk.range(), walk.aggressive());
            case UnitPlan.FindSupply find -> new WalkActivity(unit, new FinderTrackerAlgorithm<>(unit.getUnitGrid(),
                    supplyFinder(find.supplyType())), false);
            case UnitPlan.FindStore ignored -> new WalkActivity(unit, new FinderTrackerAlgorithm<>(unit.getUnitGrid(),
                    new BuildingFinder(unit.getOwner(), Abilities.SUPPLY_CONTAINER)), false);
            case UnitPlan.Attack attack -> attack.ship() != null && attack.allocation() != null ? new AttackActivity(
                    unit, attack.target(), attack.allocation(), attack.ship()) : new AttackActivity(unit,
                            attack.target());
            case UnitPlan.Harvest harvest -> new HarvestActivity(unit, harvest.supply());
            case UnitPlan.Repair repair -> new RepairActivity(unit, repair.building());
            case UnitPlan.Cast cast -> new MagicActivity(unit, cast.factory());
            case UnitPlan.Stun stun -> new StunActivity(unit, stun.seconds());
            case UnitPlan.Die ignored -> new DieActivity(unit);
        };
    }

    @SuppressWarnings("unchecked")
    private SupplyFinder<Supply> supplyFinder(Class<? extends Supply> supplyType) {
        return new SupplyFinder<>(unit, (Class<Supply>) supplyType);
    }

    private void perform(UnitPlan.Operation operation) {
        switch (operation) {
            case UnitPlan.Deposit deposit -> {
                UnitSupplyContainer supplies = unit.getSupplyContainer();
                assert supplies != null : "deposit by a unit that carries nothing";
                Class<? extends Supply> type = supplies.getSupplyType();
                int deposited = deposit.building().getSupplyContainer(type).increaseSupply(supplies.getNumSupplies());
                supplies.increaseSupply(-deposited, type);
            }
            case UnitPlan.Enter enter -> enter(enter.building(), enter.withCargo());
            case UnitPlan.Place place -> place.building().place();
        }
    }

    private void enter(Building building, boolean withCargo) {
        if (withCargo && building.getAbilities().hasAbilities(Abilities.SUPPLY_CONTAINER)) {
            UnitSupplyContainer supplies = unit.getSupplyContainer();
            int carried = supplies != null ? supplies.getNumSupplies() : 0;
            if (supplies != null && unit.getAbilities().hasAbilities(Abilities.HARVEST) && carried > 0) {
                Class<? extends Supply> type = supplies.getSupplyType();
                building.getSupplyContainer(type).increaseSupply(carried);
                supplies.increaseSupply(-carried, type);
            }
            if (unit.getWeaponFactory() instanceof ThrowingFactory factory) {
                Class<? extends ThrowingWeapon> type = factory.getType();
                building.getSupplyContainer(type).increaseSupply(1);
            }
        }
        UnitContainer container = building.getUnitContainer();
        assert container != null : "entering a building without room for units";
        container.enter(unit);
    }

    private record LiveView(Unit unit) implements UnitView {
        @Override
        public int gridX() {
            return unit.getGridX();
        }

        @Override
        public int gridY() {
            return unit.getGridY();
        }

        @Override
        public boolean isDead(Target target) {
            return target.isDead();
        }

        @Override
        public boolean isCloseEnough(float range, Target target) {
            return unit.isCloseEnough(range, target);
        }

        @Override
        public boolean canReach(Selectable<?> target) {
            float range = unit.getRange(target);
            return unit.isCloseEnough(range, target, UnitGrid.LAND) || unit.isCloseEnough(range, target, UnitGrid.SEA);
        }

        @Override
        public float range(Target target) {
            return unit.getRange(target);
        }

        @Override
        public boolean carries(Class<? extends Supply> supplyType) {
            UnitSupplyContainer supplies = unit.getSupplyContainer();
            return supplies != null && supplies.getNumSupplies() > 0 && supplies.getSupplyType() == supplyType;
        }

        @Override
        public boolean carriesAnything() {
            UnitSupplyContainer supplies = unit.getSupplyContainer();
            return supplies != null && supplies.getNumSupplies() > 0;
        }

        @Override
        public boolean isFull(Class<? extends Supply> supplyType) {
            UnitSupplyContainer supplies = unit.getSupplyContainer();
            return supplies != null && supplies.getSupplyType() == supplyType && supplies.isSupplyFull();
        }

        @Override
        public boolean isEmpty(Supply supply) {
            return supply.isEmpty();
        }

        @Override
        public boolean canEnter(Building building) {
            UnitContainer container = building.getUnitContainer();
            return container != null && container.canEnter(unit);
        }

        @Override
        public boolean isPlaced(Building building) {
            return building.isPlaced();
        }

        @Override
        public boolean isDamaged(Building building) {
            return building.isDamaged();
        }

        @Override
        public boolean isPlacingLegal(Building building) {
            return building.isPlacingLegal();
        }

        @Override
        public float placingDistance(Building building) {
            return building.getSize();
        }

        @Override
        public boolean storesSupplies(Building building) {
            return building.getAbilities().hasAbilities(Abilities.SUPPLY_CONTAINER);
        }

        @Override
        public boolean sharesOwner(Building building) {
            return unit.getOwner() == building.getOwner();
        }
    }
}
