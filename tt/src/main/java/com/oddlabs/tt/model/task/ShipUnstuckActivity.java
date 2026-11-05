package com.oddlabs.tt.model.task;

import com.oddlabs.tt.gui.ToolTipBox;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.pathfinder.ShipTrajectory;
import com.oddlabs.tt.pathfinder.ShipTrajectoryPoint;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.landscape.LandscapeTarget;
import org.jspecify.annotations.NonNull;

public final class ShipUnstuckActivity implements Activity {
    private static final float SHIP_SPEED = 0.45f;
    private ShipTrajectoryPoint next_pose = null;

    private final Ship ship;

    private ShipTrajectory trajectory = null;

    private @NonNull Event outcome = Event.COMPLETED;

    public ShipUnstuckActivity(Ship ship) {
        this.ship = ship;
        var grid = ship.getUnitGrid();
        ShipTrajectoryPoint p0 = new ShipTrajectoryPoint(ship);
        for (int i = 0; i < 4; i++) {
            float angle = i * 45.0f;
            var p1 = p0.rotated(angle).moved(15);
            if (!ShipTrajectory.checkLandCollision(grid, p0, p1)) {
                if (ShipTrajectory.checkShipsCollision(grid, ship, p0, p1) == null) {
                    this.trajectory = new ShipTrajectory(ship, new LandscapeTarget(p1.gridX, p1.gridY));
                    break;
                }
            }
            p1 = p0.rotated(-angle).moved(15);
            if (!ShipTrajectory.checkLandCollision(grid, p0, p1)) {
                if (ShipTrajectory.checkShipsCollision(grid, ship, p0, p1) == null) {
                    this.trajectory = new ShipTrajectory(ship, new LandscapeTarget(p1.gridX, p1.gridY));
                    break;
                }
            }
        }
    }

    public final boolean isBlocking() {
        return true;
    }

    public final ShipTrajectory getTrajectory() {
        return trajectory;
    }

    public void appendToolTip(ToolTipBox tool_tip_box) {
        tool_tip_box.append("ShipUnstuckActivity");
    }

    @Override
    public @NonNull State animate(float t) {
        if (ship.isDead()) {
            return State.DONE;
        }

        if (t == 0.0f) {
            return State.UNINTERRUPTIBLE;
        }

        ship.setLayer(UnitGrid.SEA);

        if (trajectory == null || !trajectory.exists()) {
            outcome = Event.NO_ROUTE;
            return State.DONE;
        }

        int rowers = ship.getShipHR().countRowers() + 1;

        if (next_pose == null) {
            float speed = rowers * SHIP_SPEED;
            next_pose = trajectory.advance(speed * t);
        }

        if (trajectory.reachedGoal()) {
            return State.DONE;
        }

        ShipTrajectoryPoint fromPoint = new ShipTrajectoryPoint(ship);

        var grid = ship.getUnitGrid();

        if (fromPoint.distanceTo(next_pose) > 0.0001f && ShipTrajectory.checkShipsCollision(grid, ship, fromPoint,
                next_pose.moved(8)) != null) {
            outcome = Event.BLOCKED;
            return State.DONE;
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
    public @NonNull Event outcome() {
        return outcome;
    }

    public final void cancel() {
    }
}
