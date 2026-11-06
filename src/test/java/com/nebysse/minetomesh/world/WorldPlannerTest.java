package com.nebysse.minetomesh.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class WorldPlannerTest {

    private static Unsafe getUnsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    @Test
    void rejectsMismatchedDimension() throws Exception {
        Unsafe unsafe = getUnsafe();
        ClientLevel level = (ClientLevel) unsafe.allocateInstance(ClientLevel.class);

        Field dimField = Level.class.getDeclaredField("dimension");
        dimField.setAccessible(true);
        dimField.set(level, ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:the_nether")));

        Selection selection = Selection.of(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 64, 15));

        WorldPlanner planner = new WorldPlanner();
        assertThrows(IllegalArgumentException.class, () -> planner.plan(level, selection));
    }

    @Test
    void marksNullOrEmptyChunksAsMissing() throws Exception {
        Unsafe unsafe = getUnsafe();
        ClientLevel level = (ClientLevel) unsafe.allocateInstance(ClientLevel.class);

        Field dimField = Level.class.getDeclaredField("dimension");
        dimField.setAccessible(true);
        dimField.set(level, ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld")));

        // Mock/subclass ClientChunkCache
        ClientChunkCache chunkSource = (ClientChunkCache) unsafe.allocateInstance(ClientChunkCache.class);

        Field chunkSourceField = ClientLevel.class.getDeclaredField("chunkSource");
        chunkSourceField.setAccessible(true);
        chunkSourceField.set(level, chunkSource);

        net.minecraft.world.level.dimension.DimensionType dimType = new net.minecraft.world.level.dimension.DimensionType(
                java.util.OptionalLong.empty(),
                true, false, false, false,
                1.0, false, false,
                -64, 384, 384,
                net.minecraft.tags.BlockTags.INFINIBURN_OVERWORLD,
                ResourceLocation.parse("minecraft:overworld"),
                0.0f,
                new net.minecraft.world.level.dimension.DimensionType.MonsterSettings(false, false, net.minecraft.util.valueproviders.ConstantInt.of(0), 0));

        Field regField = Level.class.getDeclaredField("dimensionTypeRegistration");
        regField.setAccessible(true);
        regField.set(level, net.minecraft.core.Holder.direct(dimType));

        Selection selection = Selection.of(
                new BlockPoint("minecraft:overworld", 0, 0, 0),
                new BlockPoint("minecraft:overworld", 15, 64, 15));

        WorldPlanner planner = new WorldPlanner();
        // Since getChunkSource().getChunk(...) will return null by default on allocateInstance,
        // chunk (0, 0) should be treated as missing chunk!
        ExportPlan plan = planner.plan(level, selection);

        assertTrue(plan.sections().isEmpty(), "No sections should be produced for missing chunk");
        assertEquals(1, plan.missingChunks().size());
        assertEquals(0, plan.missingChunks().get(0).chunkX());
        assertEquals(0, plan.missingChunks().get(0).chunkZ());
    }
}
