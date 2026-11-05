package com.oddlabs.tt.tutorial;

import com.oddlabs.tt.form.TutorialForm;
import com.oddlabs.tt.model.task.Order;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;

public final class MoveUnitTrigger extends TutorialTrigger {
    public MoveUnitTrigger(@NonNull Player local_player) {
        super(1f, 2f, "move_unit");
        local_player.enableMoving(true);
    }

    @Override
    protected void run(@NonNull Tutorial tutorial) {
        for (var s : tutorial.getViewer().getSelection().getCurrentSelection().getSet()) {
            if (s.getPrimaryTask() instanceof Order.Walk) {
                tutorial.done(TutorialForm.TUTORIAL_CAMERA);
            }
        }
    }
}
