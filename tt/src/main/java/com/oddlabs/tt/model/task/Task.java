package com.oddlabs.tt.model.task;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Selectable;
import org.jspecify.annotations.NullMarked;

/**
 * What a selectable is doing, as reported by {@link Selectable#getPrimaryTask()}. Units report their
 * order, else their interruption, else their posture; ships report their voyage; everything else is
 * inert.
 */
@NullMarked
public sealed interface Task permits Posture, Order, Interruption, Voyage, Task.Inert {
    /** Groups selectables doing the same job. */
    default String key(Selectable<?> selectable) {
        return getClass().getName();
    }

    /** Does nothing: buildings, ship proxies and docked ships. */
    record Inert() implements Task {
        @Override
        public String key(Selectable<?> selectable) {
            Abilities abilities = selectable.getAbilities();
            return getClass().getName() + abilities.hasAbilities(Abilities.BUILD_ARMIES) + abilities.hasAbilities(
                    Abilities.REPRODUCE) + abilities.hasAbilities(Abilities.ATTACK) + abilities.hasAbilities(
                            Abilities.SAIL);
        }
    }
}
