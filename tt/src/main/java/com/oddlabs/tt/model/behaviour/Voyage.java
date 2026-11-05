package com.oddlabs.tt.model.behaviour;

import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NullMarked;

/** A ship under way: it slides off the shore once, then sails, working itself loose when stuck. */
@NullMarked
public record Voyage(Target target, Phase phase, int failedUnstucks) implements Task {
    public enum Phase {
        SLIDING,
        SAILING,
        UNSTICKING
    }

    public Voyage(Target target) {
        this(target, Phase.SAILING, 0);
    }
}
