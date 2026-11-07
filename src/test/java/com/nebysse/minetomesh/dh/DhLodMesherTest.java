package com.nebysse.minetomesh.dh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nebysse.minetomesh.scene.MaterialKey;
import com.nebysse.minetomesh.scene.PrimitiveAccumulator;
import com.nebysse.minetomesh.world.BlockPoint;
import com.nebysse.minetomesh.world.Selection;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class DhLodMesherTest {

    @Test
    void meshesSimpleColumnWithinSelection() {
        Selection selection = new Selection(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 63, 15));

        IDhApiBlockStateWrapper blockState = new IDhApiBlockStateWrapper() {
            @Override public boolean isAir() { return false; }
            @Override public boolean isSolid() { return true; }
            @Override public boolean isLiquid() { return false; }
            @Override public int getOpacity() { return 15; }
            @Override public String getSerialString() { return "minecraft:stone"; }
            @Override public byte getMaterialId() { return 2; }
            @Override public Object getWrappedMcObject() { return null; }
        };

        IDhApiBiomeWrapper biome = new IDhApiBiomeWrapper() {
            @Override public String getName() { return "minecraft:plains"; }
            @Override public String getSerialString() { return "minecraft:plains"; }
            @Override public Object getWrappedMcObject() { return null; }
        };

        DhApiTerrainDataPoint dp = DhApiTerrainDataPoint.create(
                (byte) 0, 15, 15, 10, 20, blockState, biome);

        DhApiTerrainDataPoint[][][] chunkData = new DhApiTerrainDataPoint[16][16][];
        chunkData[0][0] = new DhApiTerrainDataPoint[] { dp };

        PrimitiveAccumulator accumulator = new PrimitiveAccumulator("test_dh_lod");
        DhLodMesher.meshChunk(null, 0, 0, chunkData, selection, 0, accumulator);

        PrimitiveAccumulator.SealResult sealed = accumulator.seal();
        assertFalse(sealed.primitives().isEmpty(), "Expected meshed primitives");
        assertEquals(1, sealed.primitives().size());
        assertEquals(24, sealed.primitives().get(0).vertices().size());
    }

    @Test
    void airBlockIsSkipped() {
        Selection selection = new Selection(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 63, 15));

        IDhApiBlockStateWrapper airBlockState = new IDhApiBlockStateWrapper() {
            @Override public boolean isAir() { return true; }
            @Override public boolean isSolid() { return false; }
            @Override public boolean isLiquid() { return false; }
            @Override public int getOpacity() { return 0; }
            @Override public String getSerialString() { return "minecraft:air"; }
            @Override public byte getMaterialId() { return 14; }
            @Override public Object getWrappedMcObject() { return null; }
        };

        DhApiTerrainDataPoint dp = DhApiTerrainDataPoint.create(
                (byte) 0, 15, 15, 10, 20, airBlockState, null);

        DhApiTerrainDataPoint[][][] chunkData = new DhApiTerrainDataPoint[16][16][];
        chunkData[0][0] = new DhApiTerrainDataPoint[] { dp };

        PrimitiveAccumulator accumulator = new PrimitiveAccumulator("test_dh_air");
        DhLodMesher.meshChunk(null, 0, 0, chunkData, selection, 0, accumulator);

        PrimitiveAccumulator.SealResult sealed = accumulator.seal();
        assertTrue(sealed.primitives().isEmpty(), "Air block must produce no geometry");
    }

    @Test
    void clipsGeometryOutsideSelection() {
        Selection selection = new Selection(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 15, 15));

        IDhApiBlockStateWrapper stone = new IDhApiBlockStateWrapper() {
            @Override public boolean isAir() { return false; }
            @Override public boolean isSolid() { return true; }
            @Override public boolean isLiquid() { return false; }
            @Override public int getOpacity() { return 15; }
            @Override public String getSerialString() { return "minecraft:stone"; }
            @Override public byte getMaterialId() { return 2; }
            @Override public Object getWrappedMcObject() { return null; }
        };

        DhApiTerrainDataPoint dp = DhApiTerrainDataPoint.create(
                (byte) 0, 15, 15, 50, 60, stone, null);

        DhApiTerrainDataPoint[][][] chunkData = new DhApiTerrainDataPoint[16][16][];
        chunkData[0][0] = new DhApiTerrainDataPoint[] { dp };

        PrimitiveAccumulator accumulator = new PrimitiveAccumulator("test_dh_clipped");
        DhLodMesher.meshChunk(null, 0, 0, chunkData, selection, 0, accumulator);

        PrimitiveAccumulator.SealResult sealed = accumulator.seal();
        assertTrue(sealed.primitives().isEmpty(), "Outside Y range must be clipped away");
    }

    @Test
    void resolvesMaterialAndGenericFallback() {
        MaterialKey nullMat = DhMaterialResolver.resolveMaterial(null);
        assertEquals(DhMaterialResolver.GENERIC_LOD_MATERIAL, nullMat);

        IDhApiBlockStateWrapper customState = new IDhApiBlockStateWrapper() {
            @Override public boolean isAir() { return false; }
            @Override public boolean isSolid() { return true; }
            @Override public boolean isLiquid() { return false; }
            @Override public int getOpacity() { return 15; }
            @Override public String getSerialString() { return "minecraft:dirt"; }
            @Override public byte getMaterialId() { return 5; }
            @Override public Object getWrappedMcObject() { return null; }
        };
        MaterialKey dirtMat = DhMaterialResolver.resolveMaterial(customState);
        assertNotNull(dirtMat);
        assertTrue(dirtMat.texture().sourceId().contains("minecraft_dirt"));
    }

    @Test
    void nullWrappersAndInvertedYBoundsDoNotThrow() {
        Selection selection = new Selection(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 63, 15));

        // Datapoint with null blockState and null biome, and inverted Y (bottom=30, top=10)
        DhApiTerrainDataPoint dp = DhApiTerrainDataPoint.create(
                (byte) 0, 15, 15, 30, 10, null, null);

        DhApiTerrainDataPoint[][][] chunkData = new DhApiTerrainDataPoint[16][16][];
        chunkData[0][0] = new DhApiTerrainDataPoint[] { dp };

        PrimitiveAccumulator accumulator = new PrimitiveAccumulator("test_dh_null_wrappers");
        DhLodMesher.meshChunk(null, 0, 0, chunkData, selection, 0, accumulator);

        PrimitiveAccumulator.SealResult sealed = accumulator.seal();
        assertFalse(sealed.primitives().isEmpty(), "Expected meshed box even with null wrappers");
        assertEquals(1, sealed.primitives().size());
        assertEquals(DhMaterialResolver.GENERIC_LOD_MATERIAL, sealed.primitives().get(0).material());
    }
}
