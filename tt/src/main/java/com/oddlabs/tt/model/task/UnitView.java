package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;

/** What a unit's decisions may read about it and the world around it. Nothing here changes anything. */
@NullMarked
public interface UnitView {
    int gridX();

    int gridY();

    boolean isDead(Target target);

    boolean isCloseEnough(float range, Target target);

    /** Whether the target is within attack range on land or at sea. */
    boolean canReach(Selectable<?> target);

    float range(Target target);

    /** Whether the unit carries supplies of exactly this type. */
    boolean carries(Class<? extends Supply> supplyType);

    boolean carriesAnything();

    /** Whether the unit carries as much of this supply type as it can. */
    boolean isFull(Class<? extends Supply> supplyType);

    boolean isEmpty(Supply supply);

    boolean canEnter(Building building);

    boolean isPlaced(Building building);

    boolean isDamaged(Building building);

    boolean isPlacingLegal(Building building);

    /** How close the unit must stand to place the building. */
    float placingDistance(Building building);

    boolean storesSupplies(Building building);

    boolean sharesOwner(Building building);
}
