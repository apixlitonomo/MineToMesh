package com.nebysse.minetomesh.dh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nebysse.minetomesh.job.DhPipelineHelper;
import com.nebysse.minetomesh.texture.TextureRegistry;
import com.nebysse.minetomesh.world.BlockPoint;
import com.nebysse.minetomesh.world.ExportPlan;
import com.nebysse.minetomesh.world.Selection;
import java.util.List;
import org.junit.jupiter.api.Test;

class DhPipelineHelperTest {

    @Test
    void nullMissingChunksReturnsCleanResult() {
        Selection selection = new Selection(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 63, 15));

        DhPipelineHelper.DhLodResult result =
                DhPipelineHelper.captureLodChunks(null, selection, null, new TextureRegistry());

        assertNotNull(result);
        assertNotNull(result.reportStats());
        assertEquals(0, result.reportStats().lodChunksAttempted());
        assertEquals(0, result.reportStats().lodChunksFilled());
        assertEquals(0, result.reportStats().lodChunksFailed());
        assertTrue(result.remainingMissing().isEmpty());
    }

    @Test
    void disabledFallbackReturnsAllMissingWithoutError() {
        DhConfig.setLodFallbackEnabled(false);
        try {
            Selection selection = new Selection(
                    new BlockPoint("minecraft:overworld", 0, 0, 0),
                    new BlockPoint("minecraft:overworld", 15, 63, 15));
            List<ExportPlan.MissingChunk> missing = List.of(
                    new ExportPlan.MissingChunk(0, 0),
                    new ExportPlan.MissingChunk(1, 1));

            DhPipelineHelper.DhLodResult result =
                    DhPipelineHelper.captureLodChunks(null, selection, missing, new TextureRegistry());

            assertNotNull(result);
            assertEquals(2, result.remainingMissing().size());
            assertEquals(0, result.reportStats().lodChunksAttempted());
        } finally {
            DhConfig.setLodFallbackEnabled(null);
        }
    }

    @Test
    void unresolvableLevelGracefullyRetainsAllMissingChunks() {
        Selection selection = new Selection(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 63, 15));
        List<ExportPlan.MissingChunk> missing = List.of(
                new ExportPlan.MissingChunk(5, 5),
                new ExportPlan.MissingChunk(6, 6));

        // level is null or has no matching DH level wrapper
        DhPipelineHelper.DhLodResult result =
                DhPipelineHelper.captureLodChunks(null, selection, missing, new TextureRegistry());

        assertNotNull(result);
        assertEquals(2, result.remainingMissing().size());
        assertEquals(0, result.reportStats().lodChunksAttempted());
        assertNotNull(result.reportStats().note());
    }
}
