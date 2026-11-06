package com.nebysse.minetomesh.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldPlanner.class);

    public ExportPlan plan(ClientLevel level, Selection selection) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(selection, "selection");
        String dimension = level.dimension().location().toString();
        if (!dimension.equals(selection.min().dimension())) {
            throw new IllegalArgumentException("Selection dimension does not match the active client level");
        }

        int minY = Math.max(selection.min().y(), level.getMinBuildHeight());
        int maxY = Math.min(selection.max().y(), level.getMaxBuildHeight() - 1);
        if (minY > maxY) {
            return new ExportPlan(selection, List.of(), List.of());
        }

        int minChunkX = Math.floorDiv(selection.min().x(), 16);
        int maxChunkX = Math.floorDiv(selection.max().x(), 16);
        int minChunkZ = Math.floorDiv(selection.min().z(), 16);
        int maxChunkZ = Math.floorDiv(selection.max().z(), 16);
        int minSectionY = Math.floorDiv(minY, 16);
        int maxSectionY = Math.floorDiv(maxY, 16);
        List<ExportPlan.SectionWork> sections = new ArrayList<>();
        List<ExportPlan.MissingChunk> missing = new ArrayList<>();

        ChunkPos playerChunk = null;
        Integer renderDistance = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                if (mc.player != null) {
                    playerChunk = mc.player.chunkPosition();
                }
                if (mc.options != null && mc.options.renderDistance() != null) {
                    renderDistance = mc.options.renderDistance().get();
                }
            }
        } catch (Throwable ignored) {
        }

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!isChunkUsable(level, chunkX, chunkZ, playerChunk, renderDistance)) {
                    missing.add(new ExportPlan.MissingChunk(chunkX, chunkZ));
                    continue;
                }
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int sectionMinX = Math.max(selection.min().x(), chunkX * 16);
                    int sectionMaxX = Math.min(selection.max().x(), chunkX * 16 + 15);
                    int sectionMinY = Math.max(minY, sectionY * 16);
                    int sectionMaxY = Math.min(maxY, sectionY * 16 + 15);
                    int sectionMinZ = Math.max(selection.min().z(), chunkZ * 16);
                    int sectionMaxZ = Math.min(selection.max().z(), chunkZ * 16 + 15);
                    sections.add(new ExportPlan.SectionWork(
                            new ChunkSectionRef(chunkX, sectionY, chunkZ),
                            sectionMinX, sectionMinY, sectionMinZ,
                            sectionMaxX, sectionMaxY, sectionMaxZ));
                }
            }
        }

        String playerPosStr = playerChunk != null ? ("[" + playerChunk.x + ", " + playerChunk.z + "]") : "unknown";
        String rdStr = renderDistance != null ? String.valueOf(renderDistance) : "unknown";
        LOGGER.info("Export plan created: sections={}, missingChunks={}, playerChunk={}, renderDistance={}",
                sections.size(), missing.size(), playerPosStr, rdStr);

        return new ExportPlan(selection, sections, missing);
    }

    private static boolean isChunkUsable(
            ClientLevel level,
            int chunkX,
            int chunkZ,
            ChunkPos playerChunk,
            Integer renderDistance) {
        // Strict check: chunk must be currently loaded and non-empty in the client chunk source
        LevelChunk chunk;
        try {
            chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        } catch (Throwable t) {
            return false;
        }

        if (chunk == null || chunk instanceof EmptyLevelChunk || chunk.isEmpty()) {
            return false;
        }

        // Render distance heuristic: if player position and RD are known, chunks beyond RD are treated as missing
        if (playerChunk != null && renderDistance != null) {
            int chebyshev = Math.max(Math.abs(chunkX - playerChunk.x), Math.abs(chunkZ - playerChunk.z));
            if (chebyshev > renderDistance) {
                return false;
            }
        }

        return true;
    }
}
