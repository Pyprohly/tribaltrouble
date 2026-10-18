package com.oddlabs.geometry;

import org.jspecify.annotations.NonNull;

import java.util.Locale;

/**
 * One line of lights.txt: a spot in a sprite's space where the glow of one of its textures lights the surroundings at
 * night. Color, reach and strength are kept as the registry spelled them.
 */
public record LightLine(@NonNull String group, @NonNull String name, int texture, @NonNull String color,
                        @NonNull String reach, @NonNull String strength, float x, float y, float z) {

    private static final int COLUMNS = 9;
    private static final String POSITION_FORMAT = "%.2f";

    public @NonNull String toLine() {
        return String.join(Listings.SEPARATOR, group, name, Integer.toString(texture), color, reach, strength,
                String.format(Locale.ROOT, POSITION_FORMAT, x), String.format(Locale.ROOT, POSITION_FORMAT, y),
                String.format(Locale.ROOT, POSITION_FORMAT, z));
    }

    public static @NonNull LightLine parse(@NonNull String line) {
        String[] f = Listings.fields(line, COLUMNS);
        return new LightLine(f[0], f[1], Integer.parseInt(f[2]), f[3], f[4], f[5], Float.parseFloat(f[6]),
                Float.parseFloat(f[7]), Float.parseFloat(f[8]));
    }
}
