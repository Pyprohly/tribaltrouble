package com.oddlabs.converter;

import com.oddlabs.converter.xml.MeshFile;
import com.oddlabs.converter.xml.Polygon;
import com.oddlabs.converter.xml.Vertex;
import com.oddlabs.converter.xml.Xml;
import org.jspecify.annotations.NonNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Where a glow lights from: a few points among the triangles that show the bright part of the glow picture. */
final class LightSpots {
    private static final int MAX_SPOTS = 4;
    private static final float GLOWING = 0.3f;
    private static final int SETTLE_PASSES = 10;

    private LightSpots() {
    }

    /** Up to MAX_SPOTS points as x y z, in the model's space times scale; none when nothing glows. */
    static @NonNull List<float @NonNull []> find(@NonNull Path mesh, @NonNull Path glow,
            float scale) throws IOException {
        BufferedImage image = ImageIO.read(glow.toFile());
        if (image == null)
            throw new IOException("Cannot read the glow picture " + glow);
        // x y z weight of every glowing triangle
        List<float[]> glowing = new ArrayList<>();
        for (Polygon polygon : Xml.read(mesh, MeshFile.class).polygons().triangles()) {
            float x = 0, y = 0, z = 0, u = 0, v = 0;
            for (Vertex vertex : polygon.vertices()) {
                x += vertex.x() / Polygon.CORNERS;
                y += vertex.y() / Polygon.CORNERS;
                z += vertex.z() / Polygon.CORNERS;
                u += vertex.u() / Polygon.CORNERS;
                v += vertex.v() / Polygon.CORNERS;
            }
            // Texture rows run from the top of the picture, v from its bottom.
            int px = Math.min(image.getWidth() - 1, (int) (fraction(u) * image.getWidth()));
            int py = Math.min(image.getHeight() - 1, (int) ((1f - fraction(v)) * image.getHeight()));
            int argb = image.getRGB(px, py);
            float brightest = Math.max(argb >> 16 & 0xff, Math.max(argb >> 8 & 0xff, argb & 0xff)) / 255f;
            float weight = brightest * (argb >>> 24) / 255f;
            if (weight > GLOWING)
                glowing.add(new float[]{x * scale, y * scale, z * scale, weight});
        }
        if (glowing.isEmpty())
            return List.of();
        // Spread the spots as far apart as the glow goes, then settle each in the middle of the glow nearest to it.
        List<float[]> spots = new ArrayList<>();
        spots.add(Arrays.copyOf(glowing.stream().max(Comparator.comparingDouble(p -> p[3])).orElseThrow(), 3));
        while (spots.size() < Math.min(MAX_SPOTS, glowing.size())) {
            float[] farthest = glowing.stream().max(Comparator.comparingDouble(
                    p -> distanceSquared(spots.get(nearest(spots, p)), p))).orElseThrow();
            spots.add(Arrays.copyOf(farthest, 3));
        }
        for (int pass = 0; pass < SETTLE_PASSES; pass++) {
            float[][] sums = new float[spots.size()][4];
            for (float[] p : glowing) {
                float[] sum = sums[nearest(spots, p)];
                for (int k = 0; k < 3; k++)
                    sum[k] += p[k] * p[3];
                sum[3] += p[3];
            }
            for (int s = 0; s < spots.size(); s++) {
                if (sums[s][3] > 0)
                    spots.set(s,
                            new float[]{sums[s][0] / sums[s][3], sums[s][1] / sums[s][3], sums[s][2] / sums[s][3]});
            }
        }
        return spots;
    }

    private static float fraction(float f) {
        return f - (float) Math.floor(f);
    }

    private static int nearest(@NonNull List<float @NonNull []> spots, float @NonNull [] p) {
        int best = 0;
        for (int s = 1; s < spots.size(); s++) {
            if (distanceSquared(spots.get(s), p) < distanceSquared(spots.get(best), p))
                best = s;
        }
        return best;
    }

    private static float distanceSquared(float @NonNull [] a, float @NonNull [] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        float dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
