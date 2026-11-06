package com.oddlabs.tt.model.task;

import com.oddlabs.tt.gui.ToolTipBox;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.pathfinder.ShipTrajectory;
import com.oddlabs.tt.pathfinder.ShipTrajectoryPoint;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NonNull;

public final class SailActivity implements Activity {
    private static final float SHIP_SPEED = 0.45f;
    private ShipTrajectoryPoint next_pose = null;

    private final Ship ship;
    private final Target target;

    private int prev_target_x = 0;
    private int prev_target_y = 0;

    private ShipTrajectory trajectory = null;

    private boolean blocked = false;
    private @NonNull Reason outcome = Reason.COMPLETED;

    public SailActivity(Ship ship, Target t) {
        this.ship = ship;
        this.target = t;
    }

    public void replanIfNeeded() {
        boolean no_traj = (trajectory == null || !trajectory.exists());
        boolean ship_moved = (target instanceof Ship) && (prev_target_x != target.getGridX()
                || prev_target_y != target.getGridY());
        if (no_traj || ship_moved) {
            this.trajectory = new ShipTrajectory(ship, target);
            this.prev_target_x = target.getGridX();
            this.prev_target_y = target.getGridY();
        }
    }

    public final boolean isBlocking() {
        return blocked;
    }

    public final ShipTrajectory getTrajectory() {
        return trajectory;
    }

    public void appendToolTip(ToolTipBox tool_tip_box) {
        tool_tip_box.append("SailActivity: ");
        if (blocked) {
            tool_tip_box.append("BLOCKED");
        } else {
            tool_tip_box.append("MOVING");
        }
    }

    @Override
    public @NonNull State animate(float t) {
        if (ship.isDead()) {
            return State.DONE;
        }

        if (t == 0.0f) {
            return State.UNINTERRUPTIBLE;
        }

        replanIfNeeded();

        ship.setLayer(UnitGrid.SEA);

        if (trajectory == null || !trajectory.exists()) {
            outcome = Reason.NO_ROUTE;
            return State.DONE;
        }

        int rowers = ship.getShipHR().countRowers() + 1;

        if (next_pose == null) {
            float speed = rowers * SHIP_SPEED * (ship.getShipHR().hasChieftain() ? 2 : 1);
            next_pose = trajectory.advance(speed * t);
        }

        if (trajectory.reachedGoal()) {
            return State.DONE;
        }

        ShipTrajectoryPoint fromPoint = new ShipTrajectoryPoint(ship);

        var grid = ship.getUnitGrid();
        if (fromPoint.distanceTo(next_pose) > 0.0001f) {
            var blockingShip = ShipTrajectory.checkShipsCollision(grid, ship, fromPoint, next_pose.moved(8));
            if (blockingShip != null) {
                // If it's an enemy ship, stand your ground and fight! Do not escape!
                // Otherwise if it's one of your own or an ally, let it pass.
                if (blockingShip.getOwner().getPlayerInfo().getTeam() == ship.getOwner().getPlayerInfo().getTeam()) {
                    outcome = Reason.BLOCKED;
                    return State.DONE;
                }
                return State.INTERRUPTIBLE;
            }
            if (ShipTrajectory.checkLandCollision(grid, fromPoint, next_pose)) {
                outcome = Reason.BLOCKED;
                return State.DONE;
            }
        }

        ship.free();
        ship.setPosition(next_pose.positionX, next_pose.positionY);
        ship.setGridPosition(next_pose.gridX, next_pose.gridY);
        ship.setDirection(next_pose.directionX, next_pose.directionY);
        next_pose = null;
        ship.occupy();

        return State.UNINTERRUPTIBLE;
    }

    @Override
    public @NonNull Reason outcome() {
        return outcome;
    }

    public final void cancel() {
    }
}
