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
 * Runs a unit's tasks: the imperative shell around {@link UnitReducer}. Every {@link Event} is
 * reduced into the unit's state, and the {@link UnitCommand}s that come back are carried out on the
 * running and the parked activity, or on the world.
 */
@NullMarked
public final class UnitController implements TaskRunner, StateController<UnitState, Event> {
    private final Unit unit;
    private final ReducerLoop<UnitState, Event, List<UnitCommand>> loop;

    private @Nullable Activity running;
    private @Nullable Activity parked;

    public UnitController(Unit unit) {
        this.unit = unit;
        this.loop = new ReducerLoop<>(
                new UnitReducer(new LiveUnitView(unit)),
                new UnitState(new TaskStack(new Posture.Idle(true))),
                this::executeAll
        );
    }

    /** Takes the first decision; called once the unit is far enough constructed to act. */
    public void start() {
        dispatch(new Event.Started());
    }

    @Override
    public UnitState getState() {
        return loop.state();
    }

    @Override
    public Task getPrimaryTask() {
        return loop.state().tasks().primary();
    }

    @Override
    public @Nullable Activity getActivity() {
        return running;
    }

    public void order(@Nullable Order order) {
        dispatch(new Event.Ordered(order));
    }

    public void stun(float seconds) {
        dispatch(new Event.Stunned(seconds));
    }

    /** Casts a spell; a stunned unit casts it once the stun wears off. */
    public void cast(MagicFactory factory, boolean clearOrder) {
        dispatch(new Event.Cast(factory, clearOrder));
    }

    public void die() {
        dispatch(new Event.Died());
    }

    public void mountTower() {
        dispatch(new Event.MountedTower());
    }

    public void mountShip(Ship ship, ShipAllocation allocation) {
        dispatch(new Event.MountedShip(ship, allocation));
    }

    public void unmount() {
        dispatch(new Event.Unmounted());
    }

    @Override
    public void dispatch(Event event) {
        loop.dispatch(event);
    }

    @Override
    public void animate(float t) {
        Activity activity = running;
        if (activity == null) {
            return;
        }
        loop.report(() -> switch (activity.animate(t)) {
            case DONE -> new Event.Report.Finished(activity.outcome());
            case INTERRUPTIBLE -> Event.Report.Progressed.INTERRUPTIBLE;
            case UNINTERRUPTIBLE -> Event.Report.Progressed.UNINTERRUPTIBLE;
        });
    }

    private void executeAll(List<UnitCommand> commands) {
        for (UnitCommand command : commands) {
            execute(command);
        }
    }

    private void execute(UnitCommand command) {
        switch (command) {
            case UnitCommand.Cancel ignored -> {
                Activity activity = running;
                assert activity != null : "cancelling with nothing running";
                activity.cancel();
                running = null;
            }
            case UnitCommand.Park ignored -> {
                assert running != null : "parking with nothing running";
                parked = running;
                running = null;
            }
            case UnitCommand.Release ignored -> running = null;
            case UnitCommand.Discard ignored -> {
                Activity activity = parked;
                assert activity != null : "discarding with nothing parked";
                activity.cancel();
                parked = null;
            }
            case UnitCommand.Resume ignored -> {
                assert parked != null : "resuming with nothing parked";
                running = parked;
                parked = null;
            }
            case UnitCommand.Start start -> running = create(start.run());
            case UnitCommand.Perform perform -> {
                perform(perform.operation());
                loop.dispatch(unit.isDead() ? new Event.Report.Removed() : new Event.Report.Performed());
            }
        }
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
            case UnitPlan.Attack attack -> {
                Seat seat = attack.seat();
                yield seat != null ? new AttackActivity(unit, attack.target(), seat.allocation(),
                        seat.ship()) : new AttackActivity(unit, attack.target());
            }
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

    private record LiveUnitView(Unit unit) implements UnitView {
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
