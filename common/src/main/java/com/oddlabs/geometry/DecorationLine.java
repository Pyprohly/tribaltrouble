package com.oddlabs.geometry;

import org.jspecify.annotations.NonNull;

/** One line of decorations.txt: scenery the game scatters on the named grounds, count per map. */
public record DecorationLine(@NonNull String group, @NonNull String name, @NonNull String grounds, int count,
                             @NonNull String event) {

    private static final int COLUMNS = 5;

    public @NonNull String toLine() {
        return String.join(Listings.SEPARATOR, group, name, grounds, Integer.toString(count), event);
    }

    public static @NonNull DecorationLine parse(@NonNull String line) {
        String[] f = Listings.fields(line, COLUMNS);
        return new DecorationLine(f[0], f[1], f[2], Integer.parseInt(f[3]), f[4]);
    }
}
