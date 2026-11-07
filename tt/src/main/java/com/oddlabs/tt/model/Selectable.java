package com.oddlabs.tt.model;

import com.oddlabs.tt.animation.Animated;
import com.oddlabs.tt.model.task.Activity;
import com.oddlabs.tt.model.task.TaskRunner;
import com.oddlabs.tt.model.task.TransitionTrace;
import com.oddlabs.tt.model.task.Task;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.ScanFilter;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.util.StateChecksum;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public abstract class Selectable<T extends Template> extends Model implements Target, Animated, ModelToolTip {
    private final @NonNull Player owner;
    private final Abilities abilities = new Abilities(Abilities.NONE);
    private final @NonNull T template;

    private boolean dead;

    private int grid_x;
    private int grid_y;
    private int layer;

    protected Selectable(@NonNull Player owner, @NonNull T template) {
        super(owner.getWorld());
        this.owner = owner;
        this.template = template;
        this.layer = UnitGrid.LAND;
    }

    public void setLayer(int layer) {
        this.layer = layer;
    }

    public int getLayer() {
        return layer;
    }

    @Override
    public final float getShadowDiameter() {
        return template.getShadowDiameter();
    }

    public final @NonNull T getTemplate() {
        return template;
    }

    public float getDefenseChance() {
        return template.getDefenseChance();
    }

    @Override
    public final float getNoDetailSize() {
        return template.getNoDetailSize();
    }

    public abstract boolean isEnabled();

    public float getHitOffsetZ() {
        return template.getHitOffsetZ(0);
    }

    protected abstract @NonNull TaskRunner getTaskRunner();

    /** What the selectable is doing, for the AI, the GUI and triggers to inspect. */
    public final @NonNull Task getPrimaryTask() {
        return getTaskRunner().getPrimaryTask();
    }

    /** Groups selectables doing the same job. */
    public final @NonNull String getActivityKey() {
        return getPrimaryTask().key(this);
    }

    @Override
    public final void animate(float t) {
        getTaskRunner().animate(t);
        doAnimate(t);
        owner.getWorld().updateGlobalChecksum(grid_x + grid_y);
    }

    protected void doAnimate(float t) {
    }

    protected final boolean isBlocking() {
        Activity activity = getTaskRunner().getActivity();
        return activity != null && activity.isBlocking();
    }

    public final @Nullable Activity getCurrentActivity() {
        return getTaskRunner().getActivity();
    }

    public final @Nullable TransitionTrace<?, ?, ?> getTransitionTrace() {
        return getTaskRunner().getTrace();
    }

    public final @NonNull UnitGrid getUnitGrid() {
        return owner.getWorld().getUnitGrid();
    }

    public final void scanVicinity(@NonNull ScanFilter filter) {
        assert !isDead();
        getUnitGrid().scan(filter, getGridX(), getGridY(), UnitGrid.LAND);
        getUnitGrid().scan(filter, getGridX(), getGridY(), UnitGrid.SEA);
    }

    private static boolean isAdjacent(@NonNull UnitGrid unit_grid, int grid_x, int grid_y, @NonNull Occupant occ) {
        return isAdjacent(unit_grid, grid_x, grid_y, occ, UnitGrid.LAND);
    }

    private static boolean isAdjacent(@NonNull UnitGrid unit_grid, int grid_x, int grid_y, @NonNull Occupant occ,
            int layer) {
        for (int x = grid_x - 1; x <= grid_x + 1; x++) {
            for (int y = grid_y - 1; y <= grid_y + 1; y++) {
                if (unit_grid.getOccupant(x, y, layer) == occ) {
                    return true;
                }
            }
        }
        return false;
    }

    public final boolean isCloseEnough(float max_dist, @NonNull Target target) {
        assert !isDead();
        return isCloseEnough(getUnitGrid(), max_dist, getGridX(), getGridY(), target, UnitGrid.LAND);
    }

    public final boolean isCloseEnough(float max_dist, @NonNull Target target, int layer) {
        assert !isDead();
        return isCloseEnough(getUnitGrid(), max_dist, getGridX(), getGridY(), target, layer);
    }

    public static boolean isCloseEnough(@NonNull UnitGrid unit_grid, float max_dist, int grid_x, int grid_y,
            @NonNull Target target) {
        return isCloseEnough(unit_grid, max_dist, grid_x, grid_y, target, UnitGrid.LAND);

    }

    public static boolean isCloseEnough(@NonNull UnitGrid unit_grid, float max_dist, int grid_x, int grid_y,
            @NonNull Target target, int layer) {
        if (max_dist == 0f && target instanceof Occupant occupant) {
            return isAdjacent(unit_grid, grid_x, grid_y, occupant, layer);
        } else {
            int dx = grid_x - target.getGridX();
            int dy = grid_y - target.getGridY();
            int dist_squared = dx * dx + dy * dy;
            float max_dist_squared = max_dist * max_dist;
            return dist_squared <= max_dist_squared;
        }
    }

    @Override
    protected final void register() {
        super.register();
        owner.getWorld().getAnimationManagerGameTime().registerAnimation(this);
        enable();
    }

    /** A player's or AI's command: it replaces whatever the selectable was ordered to do before. */
    public final void initTarget(@Nullable Target target, @NonNull Action action, boolean aggressive) {
        assert !isDead();
        if (target == null)
            return;
        setTarget(target, action, aggressive);
    }

    protected abstract void setTarget(@NonNull Target target, @NonNull Action action, boolean aggressive);

    public abstract AttackScanFilter.@NonNull Priority getAttackPriority();

    public abstract int getStatusValue();

    public final @NonNull Abilities getAbilities() {
        return abilities;
    }

    @Override
    public final int getGridX() {
        return grid_x;
    }

    @Override
    public final int getGridY() {
        return grid_y;
    }

    public final int getIslandId() {
        return getUnitGrid().getIslandId(grid_x, grid_y);
    }

    public final void setGridPosition(int grid_x, int grid_y) {
        assert !isDead();
        assert owner.getWorld().getHeightMap().isGridInside(grid_x,
                grid_y) : grid_x + " " + grid_y + " " + this.grid_x + " " + this.grid_y;
        this.grid_x = grid_x;
        this.grid_y = grid_y;
    }

    public final @NonNull Player getOwnerNoCheck() {
        return owner;
    }

    public final @NonNull Player getOwner() {
        return getOwnerNoCheck();
    }

    @Override
    public void remove() {
        super.remove();
        owner.getWorld().getAnimationManagerGameTime().removeAnimation(this);
    }

    @Override
    public final void updateChecksum(@NonNull StateChecksum checksum) {
        /*		checksum.update(getGridX());
        		checksum.update(getGridY());
        		checksum.update(getPositionX());
        		checksum.update(getPositionY());*/
    }

    protected final void disable() {
        owner.getWorld().getNotificationListener().unregisterTarget(this);
        owner.getUnits().remove(this);
    }

    protected final void enable() {
        owner.getWorld().getNotificationListener().registerTarget(this);
        owner.getUnits().add(this);
//(new Throwable()).printStackTrace();
    }

    protected void removeDying() {
        disable();
        dead = true;
    }

    @Override
    public final boolean isDead() {
        return dead;
    }

    public void hit(int damage, float direction_x, float direction_y, @NonNull Player attacker) {
        if (owner.isEnemy(attacker))
            owner.getWorld().getNotificationListener().newAttackNotification(this);
    }


    public static <T extends Template> @NonNull Class<Selectable<T>> genericClass() {
        //noinspection unchecked
        return (Class<Selectable<T>>) (Class<?>) Selectable.class;
    }

    public static <T extends Template> Selectable<T> @NonNull [] newArray(int length) {
        //noinspection unchecked
        return new Selectable[length];
    }

    public static <T extends Template> Selectable<T> @NonNull [] newArray(
            @NonNull Selectable<T> @NonNull... selectables) {
        return selectables;
    }
}
