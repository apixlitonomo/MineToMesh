package com.nebysse.minetomesh.dh;

import com.nebysse.minetomesh.scene.ColorRgba;
import com.nebysse.minetomesh.scene.MaterialKey;
import com.nebysse.minetomesh.scene.PrimitiveAccumulator;
import com.nebysse.minetomesh.scene.PrimitiveMode;
import com.nebysse.minetomesh.scene.Vec2f;
import com.nebysse.minetomesh.scene.Vec3f;
import com.nebysse.minetomesh.scene.Vertex;
import com.nebysse.minetomesh.world.Selection;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Meshes Distant Horizons terrain data columns into axis-aligned quad/box meshes.
 */
public final class DhLodMesher {
    private static final Vec2f UV_00 = new Vec2f(0.0F, 0.0F);
    private static final Vec2f UV_10 = new Vec2f(1.0F, 0.0F);
    private static final Vec2f UV_11 = new Vec2f(1.0F, 1.0F);
    private static final Vec2f UV_01 = new Vec2f(0.0F, 1.0F);

    private static final Vec3f NORMAL_UP = new Vec3f(0.0F, 1.0F, 0.0F);
    private static final Vec3f NORMAL_DOWN = new Vec3f(0.0F, -1.0F, 0.0F);
    private static final Vec3f NORMAL_NORTH = new Vec3f(0.0F, 0.0F, -1.0F);
    private static final Vec3f NORMAL_SOUTH = new Vec3f(0.0F, 0.0F, 1.0F);
    private static final Vec3f NORMAL_WEST = new Vec3f(-1.0F, 0.0F, 0.0F);
    private static final Vec3f NORMAL_EAST = new Vec3f(1.0F, 0.0F, 0.0F);

    private DhLodMesher() {
    }

    /**
     * Converts the 3D array of DH datapoints for a chunk into axis-aligned boxes/quads.
     * Geometry coordinates use the selection's min corner as local origin (min corner = 0,0,0).
     */
    public static void meshChunk(
            IDhApiLevelWrapper levelWrapper,
            int chunkPosX,
            int chunkPosZ,
            DhApiTerrainDataPoint[][][] chunkData,
            Selection selection,
            int levelMinHeight,
            PrimitiveAccumulator accumulator) {
        Objects.requireNonNull(chunkData, "chunkData");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(accumulator, "accumulator");

        int chunkBaseX = chunkPosX << 4;
        int chunkBaseZ = chunkPosZ << 4;

        int selMinX = selection.min().x();
        int selMinY = selection.min().y();
        int selMinZ = selection.min().z();
        int selMaxX = selection.max().x();
        int selMaxY = selection.max().y();
        int selMaxZ = selection.max().z();

        for (int relX = 0; relX < chunkData.length; relX++) {
            if (chunkData[relX] == null) continue;
            for (int relZ = 0; relZ < chunkData[relX].length; relZ++) {
                DhApiTerrainDataPoint[] column = chunkData[relX][relZ];
                if (column == null || column.length == 0) continue;

                for (DhApiTerrainDataPoint dp : column) {
                    if (dp == null) continue;
                    IDhApiBlockStateWrapper blockState = dp.blockStateWrapper;
                    if (blockState != null && blockState.isAir()) {
                        continue;
                    }

                    int width = 1;
                    if (dp.detailLevel > 0) {
                        width = 1 << dp.detailLevel;
                    }

                    int worldBlockMinX = chunkBaseX + relX;
                    int worldBlockMaxX = worldBlockMinX + width - 1;
                    int worldBlockMinZ = chunkBaseZ + relZ;
                    int worldBlockMaxZ = worldBlockMinZ + width - 1;

                    // Compute vertical range.
                    // DH JavaDoc: "An unsigned block position of the bottom vertex for this LOD relative to the level's minimum height. Should be >= 0."
                    int worldYBottom = levelMinHeight + dp.bottomYBlockPos;
                    int worldYTop = levelMinHeight + dp.topYBlockPos;
                    if (worldYTop < worldYBottom) {
                        int temp = worldYBottom;
                        worldYBottom = worldYTop;
                        worldYTop = temp;
                    }

                    // Clip against selection bounds
                    int clippedMinX = Math.max(worldBlockMinX, selMinX);
                    int clippedMaxX = Math.min(worldBlockMaxX, selMaxX);
                    int clippedMinY = Math.max(worldYBottom, selMinY);
                    int clippedMaxY = Math.min(worldYTop, selMaxY);
                    int clippedMinZ = Math.max(worldBlockMinZ, selMinZ);
                    int clippedMaxZ = Math.min(worldBlockMaxZ, selMaxZ);

                    if (clippedMinX > clippedMaxX || clippedMinY > clippedMaxY || clippedMinZ > clippedMaxZ) {
                        continue;
                    }

                    // Convert to local coordinates relative to selection min
                    // One block is 1 unit. Top/Max faces are at max + 1.0f
                    float x0 = (float) (clippedMinX - selMinX);
                    float x1 = (float) (clippedMaxX - selMinX + 1);
                    float y0 = (float) (clippedMinY - selMinY);
                    float y1 = (float) (clippedMaxY - selMinY + 1);
                    float z0 = (float) (clippedMinZ - selMinZ);
                    float z1 = (float) (clippedMaxZ - selMinZ + 1);

                    MaterialKey material = DhMaterialResolver.resolveMaterial(blockState);
                    IDhApiBiomeWrapper biome = dp.biomeWrapper;
                    ColorRgba tint = DhMaterialResolver.resolveColor(
                            levelWrapper, blockState, biome, clippedMinX, clippedMinY, clippedMinZ);

                    List<Vertex> boxVertices = createBoxQuads(x0, y0, z0, x1, y1, z1, tint);
                    accumulator.append(material, PrimitiveMode.QUADS, boxVertices);
                }
            }
        }
    }

    private static List<Vertex> createBoxQuads(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            ColorRgba color) {
        List<Vertex> vertices = new ArrayList<>(24);

        // UP (+Y): (x0, y1, z1) -> (x1, y1, z1) -> (x1, y1, z0) -> (x0, y1, z0)
        vertices.add(new Vertex(new Vec3f(x0, y1, z1), NORMAL_UP, UV_00, color));
        vertices.add(new Vertex(new Vec3f(x1, y1, z1), NORMAL_UP, UV_10, color));
        vertices.add(new Vertex(new Vec3f(x1, y1, z0), NORMAL_UP, UV_11, color));
        vertices.add(new Vertex(new Vec3f(x0, y1, z0), NORMAL_UP, UV_01, color));

        // DOWN (-Y): (x0, y0, z0) -> (x1, y0, z0) -> (x1, y0, z1) -> (x0, y0, z1)
        vertices.add(new Vertex(new Vec3f(x0, y0, z0), NORMAL_DOWN, UV_00, color));
        vertices.add(new Vertex(new Vec3f(x1, y0, z0), NORMAL_DOWN, UV_10, color));
        vertices.add(new Vertex(new Vec3f(x1, y0, z1), NORMAL_DOWN, UV_11, color));
        vertices.add(new Vertex(new Vec3f(x0, y0, z1), NORMAL_DOWN, UV_01, color));

        // NORTH (-Z): (x1, y1, z0) -> (x1, y0, z0) -> (x0, y0, z0) -> (x0, y1, z0)
        vertices.add(new Vertex(new Vec3f(x1, y1, z0), NORMAL_NORTH, UV_00, color));
        vertices.add(new Vertex(new Vec3f(x1, y0, z0), NORMAL_NORTH, UV_01, color));
        vertices.add(new Vertex(new Vec3f(x0, y0, z0), NORMAL_NORTH, UV_11, color));
        vertices.add(new Vertex(new Vec3f(x0, y1, z0), NORMAL_NORTH, UV_10, color));

        // SOUTH (+Z): (x0, y1, z1) -> (x0, y0, z1) -> (x1, y0, z1) -> (x1, y1, z1)
        vertices.add(new Vertex(new Vec3f(x0, y1, z1), NORMAL_SOUTH, UV_00, color));
        vertices.add(new Vertex(new Vec3f(x0, y0, z1), NORMAL_SOUTH, UV_01, color));
        vertices.add(new Vertex(new Vec3f(x1, y0, z1), NORMAL_SOUTH, UV_11, color));
        vertices.add(new Vertex(new Vec3f(x1, y1, z1), NORMAL_SOUTH, UV_10, color));

        // WEST (-X): (x0, y1, z0) -> (x0, y0, z0) -> (x0, y0, z1) -> (x0, y1, z1)
        vertices.add(new Vertex(new Vec3f(x0, y1, z0), NORMAL_WEST, UV_00, color));
        vertices.add(new Vertex(new Vec3f(x0, y0, z0), NORMAL_WEST, UV_01, color));
        vertices.add(new Vertex(new Vec3f(x0, y0, z1), NORMAL_WEST, UV_11, color));
        vertices.add(new Vertex(new Vec3f(x0, y1, z1), NORMAL_WEST, UV_10, color));

        // EAST (+X): (x1, y1, z1) -> (x1, y0, z1) -> (x1, y0, z0) -> (x1, y1, z0)
        vertices.add(new Vertex(new Vec3f(x1, y1, z1), NORMAL_EAST, UV_00, color));
        vertices.add(new Vertex(new Vec3f(x1, y0, z1), NORMAL_EAST, UV_01, color));
        vertices.add(new Vertex(new Vec3f(x1, y0, z0), NORMAL_EAST, UV_11, color));
        vertices.add(new Vertex(new Vec3f(x1, y1, z0), NORMAL_EAST, UV_10, color));

        return vertices;
    }
}
