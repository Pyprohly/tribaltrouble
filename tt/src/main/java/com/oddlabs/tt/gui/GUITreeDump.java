package com.oddlabs.tt.gui;

import org.jspecify.annotations.NonNull;

/**
 * Text dump of the live GUI tree for scripted input: one object per line as
 * {@code depth class x y width height [text]} in framebuffer pixels with a top-left origin, matching screenshots.
 */
public final class GUITreeDump {
    private GUITreeDump() {
    }

    public static @NonNull String dump(@NonNull GUIRoot root, int framebuffer_height) {
        StringBuilder out = new StringBuilder();
        append(out, root, 0, 0, 0, root.getGlobalScale(), framebuffer_height);
        return out.toString();
    }

    private static void append(@NonNull StringBuilder out, @NonNull GUIObject object, int depth, int parent_x,
            int parent_y, float scale, int framebuffer_height) {
        int abs_x = parent_x + object.getX();
        int abs_y = parent_y + object.getY();
        int px = Math.round(abs_x * scale);
        int py = framebuffer_height - Math.round((abs_y + object.getHeight()) * scale);
        int pw = Math.round(object.getWidth() * scale);
        int ph = Math.round(object.getHeight() * scale);
        out.append(depth).append(' ').append(object.getClass().getSimpleName()).append(' ').append(px).append(
                ' ').append(py).append(' ').append(pw).append(' ').append(ph);
        if (object instanceof TextField field) {
            String text = field.getContents().replace('\n', ' ');
            if (object instanceof PasswordLine) {
                text = "*".repeat(text.length());
            }
            if (!text.isEmpty()) {
                out.append(' ').append(text);
            }
        }
        out.append('\n');
        GUIObject child = object.getFirstChild();
        while (child != null) {
            append(out, child, depth + 1, abs_x, abs_y, scale, framebuffer_height);
            child = child.getNext();
        }
    }
}
