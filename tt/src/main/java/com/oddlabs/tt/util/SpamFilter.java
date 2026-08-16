package com.oddlabs.tt.util;

import org.jspecify.annotations.NonNull;

public final class SpamFilter {
    public static @NonNull String scan(String string) {
        return string.replaceAll("\\s+", " ");
    }

    private SpamFilter() {
    }
}
