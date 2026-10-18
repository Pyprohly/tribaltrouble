package com.oddlabs.converter;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Node;

// Every attribute geometry.xml declares on a texture of a model, read and checked once.
public record TextureInfo(@NonNull String name, @Nullable String team, @Nullable String emissive,
                          @Nullable Light light) {

    private static final String LIGHT_COLOR_FORMAT = "#[0-9a-fA-F]{6}";
    private static final String LIGHT_NUMBER_FORMAT = "[0-9]+(\\.[0-9]+)?";
    private static final String LIGHT_EXAMPLE = "light_color=\"#ff8c26\" light_strength=\"1\" light_reach=\"8\"";

    // The glow lights its surroundings at night; present only with all three attributes valid.
    public record Light(@NonNull String color, @NonNull String strength, @NonNull String reach) {
    }

    static @NonNull TextureInfo read(@NonNull Node n, @NonNull String sprite) {
        String emissive = attribute(n, "emissive");
        String color = attribute(n, "light_color");
        String strength = attribute(n, "light_strength");
        String reach = attribute(n, "light_reach");
        Light light = null;
        if (color != null || strength != null || reach != null) {
            if (emissive == null || color == null || !color.matches(LIGHT_COLOR_FORMAT) || strength == null
                    || !strength.matches(LIGHT_NUMBER_FORMAT) || reach == null || !reach.matches(LIGHT_NUMBER_FORMAT))
                throw new RuntimeException(
                        "Sprite " + sprite + " needs a glow (emissive=) and all three of " + LIGHT_EXAMPLE + " to cast light");
            light = new Light(color, strength, reach);
        }
        return new TextureInfo(n.getAttributes().getNamedItem("name").getNodeValue(), attribute(n, "team"), emissive,
                light);
    }

    // The list the game loads: name, team decal and, when there is one, the glow image.
    String @NonNull [] names() {
        return emissive != null ? new String[]{name, team, emissive} : new String[]{name, team};
    }

    private static @Nullable String attribute(@NonNull Node n, @NonNull String name) {
        Node attribute = n.getAttributes().getNamedItem(name);
        return attribute != null ? attribute.getNodeValue() : null;
    }
}
