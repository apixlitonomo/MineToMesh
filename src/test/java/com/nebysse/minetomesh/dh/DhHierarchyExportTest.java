package com.nebysse.minetomesh.dh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nebysse.minetomesh.gltf.BinaryBufferWriter;
import com.nebysse.minetomesh.gltf.GltfDocumentBuilder;
import com.nebysse.minetomesh.gltf.WrittenPrimitive;
import com.nebysse.minetomesh.scene.CapturedNode;
import com.nebysse.minetomesh.scene.PrimitiveAccumulator;
import com.nebysse.minetomesh.scene.PrimitiveData;
import com.nebysse.minetomesh.usd.StreamingUsdaSession;
import com.nebysse.minetomesh.world.BlockPoint;
import com.nebysse.minetomesh.world.Selection;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DhHierarchyExportTest {
    @TempDir
    Path tempDir;

    @Test
    void gltfBuildsDistantHorizonsLodChunksHierarchy() throws Exception {
        Selection selection = new Selection(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 63, 15));

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
                (byte) 0, 15, 15, 10, 20, stone, null);
        DhApiTerrainDataPoint[][][] chunkData = new DhApiTerrainDataPoint[16][16][];
        chunkData[0][0] = new DhApiTerrainDataPoint[] { dp };

        PrimitiveAccumulator accumulator = new PrimitiveAccumulator("c_0_0");
        DhLodMesher.meshChunk(null, 0, 0, chunkData, selection, 0, accumulator);

        PrimitiveAccumulator.SealResult sealed = accumulator.seal();
        PrimitiveData primitive = sealed.primitives().get(0);

        ByteArrayOutputStream binary = new ByteArrayOutputStream();
        WrittenPrimitive written;
        try (BinaryBufferWriter writer = new BinaryBufferWriter(binary)) {
            written = new WrittenPrimitive(
                    writer.writePositions(primitive.vertices()),
                    writer.writeNormals(primitive.vertices()),
                    writer.writeTexCoords(primitive.vertices()),
                    Optional.empty(),
                    writer.writeIndices(primitive.indices()),
                    primitive.gltfMode(),
                    primitive.material());
        }

        CapturedNode lodNode = new CapturedNode("c_0_0", CapturedNode.Kind.DH_LOD,
                List.of(primitive), Map.of("chunkX", 0, "chunkZ", 0));

        GltfDocumentBuilder builder = new GltfDocumentBuilder("test.bin", Map.of());
        builder.addNode(lodNode, List.of(written));
        JsonObject document = builder.finish(binary.size());

        JsonArray nodes = document.getAsJsonArray("nodes");
        JsonObject dhRoot = null;
        JsonObject lodChunksNode = null;
        for (int i = 0; i < nodes.size(); i++) {
            JsonObject n = nodes.get(i).getAsJsonObject();
            if ("DistantHorizons".equals(n.get("name").getAsString())) {
                dhRoot = n;
            } else if ("LodChunks".equals(n.get("name").getAsString())) {
                lodChunksNode = n;
            }
        }

        assertNotNull(dhRoot, "Expected DistantHorizons hierarchy root in glTF");
        assertNotNull(lodChunksNode, "Expected LodChunks intermediate node in glTF");
        assertTrue(dhRoot.has("children"));
        assertTrue(lodChunksNode.has("children"));
    }

    @Test
    void usdaBuildsDistantHorizonsLodChunksHierarchy() throws Exception {
        Selection selection = new Selection(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 63, 15));

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
                (byte) 0, 15, 15, 10, 20, stone, null);
        DhApiTerrainDataPoint[][][] chunkData = new DhApiTerrainDataPoint[16][16][];
        chunkData[0][0] = new DhApiTerrainDataPoint[] { dp };

        PrimitiveAccumulator accumulator = new PrimitiveAccumulator("c_0_0");
        DhLodMesher.meshChunk(null, 0, 0, chunkData, selection, 0, accumulator);

        PrimitiveAccumulator.SealResult sealed = accumulator.seal();
        CapturedNode lodNode = new CapturedNode("c_0_0", CapturedNode.Kind.DH_LOD,
                sealed.primitives(), Map.of());

        try (StreamingUsdaSession usdaSession = new StreamingUsdaSession(tempDir, "test_dh", Map.of())) {
            com.nebysse.minetomesh.scene.ChunkBatch batch =
                    new com.nebysse.minetomesh.scene.ChunkBatch(List.of(lodNode), List.of(), com.nebysse.minetomesh.scene.BatchCounters.ZERO);
            usdaSession.append(batch);
            usdaSession.finish();
        }

        String usdaText = Files.readString(tempDir.resolve("test_dh.usda"));
        assertTrue(usdaText.contains("def Xform \"DistantHorizons\""));
        assertTrue(usdaText.contains("def Xform \"LodChunks\""));
        assertTrue(usdaText.contains("c_0_0"));
    }
}
