package com.nebysse.minetomesh.sable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nebysse.minetomesh.scene.ColorRgba;
import com.nebysse.minetomesh.scene.MaterialKey;
import com.nebysse.minetomesh.scene.PrimitiveAccumulator;
import com.nebysse.minetomesh.scene.PrimitiveData;
import com.nebysse.minetomesh.scene.PrimitiveMode;
import com.nebysse.minetomesh.scene.TextureKey;
import com.nebysse.minetomesh.scene.Vec2f;
import com.nebysse.minetomesh.scene.Vec3f;
import com.nebysse.minetomesh.scene.Vertex;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegionSceneTest {
    private static final MaterialKey MATERIAL = new MaterialKey(
            new TextureKey(TextureKey.Kind.ATLAS_SPRITE, "minecraft:block/stone",
                    "textures/block/stone.png"),
            MaterialKey.AlphaMode.OPAQUE,
            Optional.empty(),
            false,
            false,
            MaterialKey.BlendSemantic.STANDARD,
            MaterialKey.SamplerMode.NEAREST);

    @Test
    void worldAndSubLevelObjectsKeepTheNamesTheOutlinerShows() {
        assertEquals("world", RegionScene.objectName(0, "anything"));
        assertEquals("sub03_my_ship_", RegionScene.objectName(3, "my ship!"));
        assertEquals("sub01_unknown", RegionScene.objectName(1, null));
        assertEquals("sable/world", RegionScene.nodeName(0, "world"));
        assertEquals("sable/sub02_hull", RegionScene.nodeName(2, "hull"));
    }

    @Test
    void sanitizeKeepsOnlyFileSafeCharacters() {
        assertEquals("a_b_c-d.e_1", RegionScene.sanitize("a b/c-d.e:1"));
        assertEquals("unknown", RegionScene.sanitize("  "));
        assertEquals("unknown", RegionScene.sanitize(null));
    }

    @Test
    void nodeExtrasNameTheStructureOnlyForSubLevels() {
        assertEquals("world", RegionScene.nodeExtras(0, "world").get("coordinateSpace"));
        assertFalse(RegionScene.nodeExtras(0, "world").containsKey("subLevelName"));
        assertEquals("sub_level", RegionScene.nodeExtras(1, "hull").get("coordinateSpace"));
        assertEquals("hull", RegionScene.nodeExtras(1, "hull").get("subLevelName"));
        assertEquals(1, RegionScene.nodeExtras(1, "hull").get("spaceIndex"));
    }

    @Test
    void quadsCountAsTwoTrianglesEach() {
        PrimitiveAccumulator accumulator = new PrimitiveAccumulator("sable/world");
        accumulator.append(MATERIAL, PrimitiveMode.QUADS, quad(0.0F));
        accumulator.append(MATERIAL, PrimitiveMode.QUADS, quad(2.0F));
        List<PrimitiveData> primitives = accumulator.seal().primitives();
        assertEquals(4L, RegionScene.triangleCount(primitives));
        assertEquals(0L, RegionScene.triangleCount(List.of()));
    }

    @Test
    void vertexBoundsCoverEveryVertexAndAreNullWithoutGeometry() {
        PrimitiveAccumulator accumulator = new PrimitiveAccumulator("sable/world");
        accumulator.append(MATERIAL, PrimitiveMode.QUADS, quad(0.0F));
        accumulator.append(MATERIAL, PrimitiveMode.QUADS, quad(2.0F));
        float[] bounds = RegionScene.vertexBounds(accumulator.seal().primitives());
        assertArrayEquals(new float[] {0.0F, 0.0F, 0.0F, 3.0F, 1.0F, 2.0F}, bounds);
        assertNull(RegionScene.vertexBounds(List.of()));
    }

    /** A unit quad in the XY plane, starting at {@code x} and lying at {@code z = x}. */
    private static List<Vertex> quad(float x) {
        List<Vertex> vertices = new ArrayList<>();
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float[] corner : corners) {
            vertices.add(new Vertex(
                    new Vec3f(x + corner[0], corner[1], x),
                    new Vec3f(0.0F, 0.0F, 1.0F),
                    new Vec2f(corner[0], corner[1]),
                    ColorRgba.WHITE));
        }
        return vertices;
    }
}
