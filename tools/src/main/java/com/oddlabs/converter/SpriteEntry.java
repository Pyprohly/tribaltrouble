package com.oddlabs.converter;

import com.oddlabs.geometry.registry.Registry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Node;

import java.util.List;
import java.util.Set;

// Every attribute geometry.xml declares on a sprite, read and checked once; the node is kept for the model, skeleton
// and animation children. Checks against other sprites of the group happen where the group is known.
public record SpriteEntry(@NonNull Node node, @NonNull String name, float scale, @Nullable String base,
                          @Nullable String slot, boolean defaultOn, @NonNull String event, @Nullable String skin,
                          @Nullable String replaces, @Nullable String decoration, int count) {

    private static final Set<String> DECORATION_GROUNDS = Set.of("beach", "dirt", "grass", "snow", "land");
    private static final int DEFAULT_DECORATION_COUNT = 20;
    private static final int MAX_DECORATION_COUNT = 1000;

    static @NonNull SpriteEntry read(@NonNull Node n) {
        String name = n.getAttributes().getNamedItem("name").getNodeValue();
        String scale = attribute(n, "scale");
        String event = attribute(n, "event");
        String base = attribute(n, "base");
        String slot = attribute(n, "slot");
        String skin = attribute(n, "skin");
        String decoration = attribute(n, "decoration");
        String count = attribute(n, "count");
        if (slot != null && base == null)
            throw new RuntimeException("Sprite " + name + " has a slot but no base");
        if (decoration == null && count != null)
            throw new RuntimeException("Sprite " + name + " has a count but no decoration");
        if (decoration != null) {
            if (!DECORATION_GROUNDS.containsAll(List.of(decoration.split(",", -1))))
                throw new RuntimeException(
                        "Sprite " + name + " has decoration=\"" + decoration + "\"; use one or more of " + String.join(
                                ", ", DECORATION_GROUNDS.stream().sorted().toList()) + ", comma separated");
            if (slot != null || skin != null)
                throw new RuntimeException("Sprite " + name + " cannot be a decoration and an attachment or skin");
        }
        if (count == null)
            count = Integer.toString(DEFAULT_DECORATION_COUNT);
        if (!count.matches("[0-9]{1,4}") || Integer.parseInt(count) < 1 || Integer.parseInt(
                count) > MAX_DECORATION_COUNT)
            throw new RuntimeException(
                    "Sprite " + name + " needs a count from 1 to " + MAX_DECORATION_COUNT + ", not " + count);
        return new SpriteEntry(n, name, scale != null ? Float.parseFloat(scale) : 1f, base, slot,
                Boolean.parseBoolean(attribute(n, "default")), event != null ? event : Registry.NO_EVENT, skin,
                attribute(n, "replaces"), decoration, Integer.parseInt(count));
    }

    static @Nullable String attribute(@NonNull Node n, @NonNull String name) {
        Node attribute = n.getAttributes().getNamedItem(name);
        return attribute != null ? attribute.getNodeValue() : null;
    }
}
