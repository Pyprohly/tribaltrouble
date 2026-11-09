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
 * Runs a unit's tasks: the imperative shell around {@link UnitStateReducer}. Every {@link Event} is
 * reduced into the unit's state, and the {@link UnitCommand}s that come back are carried out on the
 * running activity and the posture's, or on the world.
 */
@NullMarked
public final class UnitStateController implements TaskRunner {
    private final Unit unit;
    private final ReducerLoop<UnitState, Event, List<UnitCommand>> loop;
    private final @Nullable TransitionTrace<UnitState, Event, List<UnitCommand>> trace;

    private @Nullable UnitActivity runningActivity;
    /**
     * The activity built for the posture's {@link UnitPlan.Lasting} plan, reused each time that plan runs. An
     * interrupt sets it aside instead of cancelling it; only {@link UnitCommand.Discard} cancels it.
     */
    private @Nullable UnitActivity postureActivity;

    public UnitStateController(Unit unit) {
        this.unit = unit;
        this.trace = TransitionTrace.ENABLED ? new TransitionTrace<>((event,
                commands) -> !(event instanceof Event.Report.Progressed) || !commands.isEmpty()) : null;
        this.loop = new ReducerLoop<UnitState, Event, List<UnitCommand>>(
                new UnitStateReducer(new LiveUnitView(unit)),
                new UnitState(new TaskSlots(new Posture.Idle(true))),
                this::executeAll,
                trace
        );
    }

    /** Takes the first decision; called once the unit is far enough constructed to act. */
    public void start() {
        dispatch(new Event.Started());
    }

    public UnitState getState() {
        return loop.state();
    }

    @Override
    public Task getPrimaryTask() {
        return loop.state().tasks().primary();
    }

    @Override
    public @Nullable Activity getRunningActivity() {
        return runningActivity;
    }

    @Override
    public @Nullable TransitionTrace<UnitState, Event, List<UnitCommand>> getTrace() {
        return trace;
    }

    public void order(Order order) {
        dispatch(new Event.Ordered(order));
    }

    /** Takes the unit's order away, leaving what it is doing for it to finish. */
    public void excuse() {
        dispatch(new Event.Excused());
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
    public void animate(float t) {
        UnitActivity activity = runningActivity;
        if (activity == null) {
            return;
        }
        loop.report(() -> {
            Activity.State progress = activity.animate(t);
            if (progress == Activity.State.DONE) {
                return new Event.Report.Finished(activity.outcome());
            }
            boolean unchanged = loop.state().execution() instanceof UnitState.Execution.Running executing
                    && executing.progress() == progress;
            return unchanged ? null : new Event.Report.Progressed(progress);
        });
    }

    private void dispatch(Event event) {
        loop.dispatch(event);
    }

    private void executeAll(List<UnitCommand> commands) {
        for (UnitCommand command : commands) {
            execute(command);
        }
        UnitState state = loop.state();
        assert (runningActivity != null) == (state.execution() instanceof UnitState.Execution.Running) : "the running activity and the unit's execution disagree";
    }

    private void execute(UnitCommand command) {
        switch (command) {
            case UnitCommand.Interrupt _ -> {
                UnitActivity activity = runningActivity;
                assert activity != null : "cancelling with nothing running";
                if (activity != null && activity != postureActivity) {
                    activity.cancel();
                }
                runningActivity = null;
            }
            case UnitCommand.Release _ -> runningActivity = null;
            case UnitCommand.Discard _ -> {
                UnitActivity activity = postureActivity;
                assert activity == null || runningActivity != activity : "discarding the running posture activity";
                if (activity != null) {
                    activity.cancel();
                    postureActivity = null;
                }
            }
            case UnitCommand.Run run -> {
                UnitActivity activity;
                if (run.run() instanceof UnitPlan.Lasting lasting) {
                    activity = postureActivity;
                    if (activity == null) {
                        activity = create(lasting);
                        postureActivity = activity;
                    }
                } else {
                    activity = create(run.run());
                }
                runningActivity = activity;
            }
            case UnitCommand.Perform perform -> {
                perform(perform.operation());
                dispatch(unit.isDead() ? new Event.Report.Removed() : new Event.Report.Performed());
            }
        }
    }

    private UnitActivity create(UnitPlan.Run run) {
        return switch (run) {
            case UnitPlan.Watch watch -> new IdleActivity(unit, watch.canMove());
            case UnitPlan.Fight fight -> new ShipAttackActivity(unit, fight.ship(), fight.allocation());
            case UnitPlan.Sit sit -> new SittingActivity(unit, sit.ship(), sit.allocation());
            case UnitPlan.Walk walk -> new WalkActivity(unit, walk.target(), walk.range(), walk.aggressive());
            case UnitPlan.FindSupply find -> new WalkActivity(unit, new FinderTrackerAlgorithm<>(unit.getUnitGrid(),
                    supplyFinder(find.supplyType())), Reason.FoundSupply::new);
            case UnitPlan.FindStore _ -> new WalkActivity(unit, new FinderTrackerAlgorithm<>(unit.getUnitGrid(),
                    new BuildingFinder(unit.getOwner(), Abilities.SUPPLY_CONTAINER)), Reason.FoundStore::new);
            case UnitPlan.Attack attack -> {
                Seat seat = attack.seat();
                yield seat != null ? new AttackActivity(unit, attack.target(), seat.allocation(),
                        seat.ship()) : new AttackActivity(unit, attack.target());
            }
            case UnitPlan.Harvest harvest -> new HarvestActivity(unit, harvest.supply());
            case UnitPlan.Repair repair -> new RepairActivity(unit, repair.building());
            case UnitPlan.Cast cast -> new MagicActivity(unit, cast.factory());
            case UnitPlan.Stun stun -> new StunActivity(unit, stun.seconds());
            case UnitPlan.Die _ -> new DieActivity(unit);
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
        public boolean canEnter(Building building) {
            UnitContainer container = building.getUnitContainer();
            return container != null && container.canEnter(unit);
        }

        @Override
        public boolean sharesOwner(Building building) {
            return unit.getOwner() == building.getOwner();
        }
    }
}
