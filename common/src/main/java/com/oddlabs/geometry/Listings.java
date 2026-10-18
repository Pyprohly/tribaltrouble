package com.oddlabs.geometry;

import org.jspecify.annotations.NonNull;

/**
 * The text files the geometry converter writes next to the binary sprites and the game reads at load. One record per
 * file holds the column order, so the writer and the reader cannot drift apart.
 */
public final class Listings {
    public static final String ATTACHMENTS = "attachments.txt";
    public static final String SKINS = "skins.txt";
    public static final String DECORATIONS = "decorations.txt";
    public static final String LIGHTS = "lights.txt";
    /** The event column of a sprite that is loaded all year. */
    public static final String NO_EVENT = "-";
    static final String SEPARATOR = " ";

    private Listings() {
    }

    static @NonNull String @NonNull [] fields(@NonNull String line, int count) {
        String[] fields = line.split(SEPARATOR);
        if (fields.length != count)
            throw new IllegalArgumentException("Expected " + count + " columns: " + line);
        return fields;
    }
}
