package com.oddlabs.geometry;

import org.jspecify.annotations.NonNull;

/** One line of skins.txt: a sprite that stands in for another sprite of its group while the skin applies. */
public record SkinLine(@NonNull String group, @NonNull String skin, @NonNull String replaces, @NonNull String name,
                       int textures, @NonNull String event) {

    private static final int COLUMNS = 6;

    public @NonNull String toLine() {
        return String.join(Listings.SEPARATOR, group, skin, replaces, name, Integer.toString(textures), event);
    }

    public static @NonNull SkinLine parse(@NonNull String line) {
        String[] f = Listings.fields(line, COLUMNS);
        return new SkinLine(f[0], f[1], f[2], f[3], Integer.parseInt(f[4]), f[5]);
    }
}
