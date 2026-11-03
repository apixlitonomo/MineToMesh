package com.nebysse.minetomesh.sable;

import com.nebysse.minetomesh.scene.PrimitiveData;
import com.nebysse.minetomesh.scene.Vertex;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure naming and measurement helpers for the Sable region export.
 *
 * <p>Nothing here touches Minecraft, so the rules that decide what the Blender outliner shows and
 * what the triangle counters report can be unit tested without a client.
 */
final class RegionScene {
    /** Prefix of every node the Sable export writes, so it never collides with a chunk node. */
    static final String NODE_PREFIX = "sable/";

    private RegionScene() {
    }

    /**
     * The object name of one coordinate space: {@code world} for the ordinary level and
     * {@code subNN_name} for each Sable sub-level.
     */
    static String objectName(int index, String name) {
        if (index == 0) {
            return "world";
        }
        return "sub" + String.format(Locale.ROOT, "%02d", index) + "_" + sanitize(name);
    }

    /** The scene node name of one coordinate space. */
    static String nodeName(int index, String name) {
        return NODE_PREFIX + objectName(index, name);
    }

    /** Extras attached to a space's node, so the structure's identity survives the import. */
    static Map<String, Object> nodeExtras(int index, String name) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("source", "sable_region");
        extras.put("coordinateSpace", index == 0 ? "world" : "sub_level");
        extras.put("spaceIndex", index);
        if (index != 0) {
            extras.put("subLevelName", name == null || name.isBlank() ? "unknown" : name);
        }
        return extras;
    }

    /** Replaces everything outside {@code [A-Za-z0-9_.-]}, so a name is safe in any file format. */
    static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Triangles the glTF output will contain for these primitives. */
    static long triangleCount(List<PrimitiveData> primitives) {
        Objects.requireNonNull(primitives, "primitives");
        long count = 0;
        for (PrimitiveData primitive : primitives) {
            int indexCount = primitive.indices().length;
            count += switch (primitive.gltfMode()) {
                case 4 -> indexCount / 3L;
                case 5, 6 -> Math.max(0, indexCount - 2L);
                default -> 0L;
            };
        }
        return count;
    }

    /**
     * The bounds of every vertex of the primitives as {@code minX, minY, minZ, maxX, maxY, maxZ},
     * or {@code null} when there is no finite vertex.
     */
    static float[] vertexBounds(List<PrimitiveData> primitives) {
        Objects.requireNonNull(primitives, "primitives");
        float[] out = null;
        for (PrimitiveData primitive : primitives) {
            for (Vertex vertex : primitive.vertices()) {
                float x = vertex.position().x();
                float y = vertex.position().y();
                float z = vertex.position().z();
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                    continue;
                }
                if (out == null) {
                    out = new float[] {x, y, z, x, y, z};
                } else {
                    out[0] = Math.min(out[0], x);
                    out[1] = Math.min(out[1], y);
                    out[2] = Math.min(out[2], z);
                    out[3] = Math.max(out[3], x);
                    out[4] = Math.max(out[4], y);
                    out[5] = Math.max(out[5], z);
                }
            }
        }
        return out;
    }
}
