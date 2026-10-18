package com.oddlabs.geometry;

import org.jspecify.annotations.NonNull;

/**
 * One line of attachments.txt: an item in a slot of its base sprite. Default-on items write 0 in the order column, so a
 * plain sort of the lines lists them first in their slot.
 */
public record AttachmentLine(@NonNull String group, @NonNull String base, @NonNull String slot, boolean defaultOn,
                             @NonNull String name, int textures, @NonNull String event) {

    private static final int COLUMNS = 7;
    private static final String DEFAULT_FIRST = "0";
    private static final String BY_NAME = "1";

    public @NonNull String toLine() {
        return String.join(Listings.SEPARATOR, group, base, slot, defaultOn ? DEFAULT_FIRST : BY_NAME, name,
                Integer.toString(textures), event);
    }

    public static @NonNull AttachmentLine parse(@NonNull String line) {
        String[] f = Listings.fields(line, COLUMNS);
        return new AttachmentLine(f[0], f[1], f[2], f[3].equals(DEFAULT_FIRST), f[4], Integer.parseInt(f[5]), f[6]);
    }
}
