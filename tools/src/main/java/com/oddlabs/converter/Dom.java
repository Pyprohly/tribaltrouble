package com.oddlabs.converter;

import org.jspecify.annotations.NonNull;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** DOM helpers for the mesh, skeleton and clip files, which are large enough to stay as plain trees. */
final class Dom {
    private Dom() {
    }

    static @NonNull Node childNamed(@NonNull Node n, @NonNull String name) {
        NodeList nl = n.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getNodeName().equals(name))
                return nl.item(i);
        }
        throw new IllegalArgumentException("Missing node: " + name);
    }
}
