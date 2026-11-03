package com.nebysse.minetomesh.sable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nebysse.minetomesh.MineToMesh;
import com.nebysse.minetomesh.capture.CoplanarQuadLayering;
import com.nebysse.minetomesh.capture.RenderTypeDescriptor;
import com.nebysse.minetomesh.capture.RenderTypeInspector;
import com.nebysse.minetomesh.job.DefaultExportPipeline;
import com.nebysse.minetomesh.output.ExportName;
import com.nebysse.minetomesh.report.ExportReport;
import com.nebysse.minetomesh.scene.BatchCounters;
import com.nebysse.minetomesh.scene.CapturedNode;
import com.nebysse.minetomesh.scene.ChunkBatch;
import com.nebysse.minetomesh.scene.Diagnostic;
import com.nebysse.minetomesh.scene.GeometryAdjustmentStats;
import com.nebysse.minetomesh.scene.MaterialKey;
import com.nebysse.minetomesh.scene.PrimitiveAccumulator;
import com.nebysse.minetomesh.scene.PrimitiveData;
import com.nebysse.minetomesh.texture.AtlasSpriteResolver;
import com.nebysse.minetomesh.texture.SpriteTextureExtractor;
import com.nebysse.minetomesh.texture.TextureRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.extensions.IBakedModelExtension;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /minetomesh sable <name>}: export the blocks inside the area the Sable blueprint tool has
 * selected, including the structures Sable keeps in sub-levels.
 *
 * <p>A saved-blueprint export has a format in between: whatever that format does not carry - a
 * caterpillar track, a wheel, a sub-level's own assembly - has to be guessed at afterwards. This
 * path reads the live world instead. The selection is the one the Sable blueprint tool is already
 * showing ({@link RegionSelection}), and the blocks are the ones the client is drawing, with the
 * block state and block-entity instance the world itself is holding.
 *
 * <p>Three coordinate facts drive the implementation, and each of them was a defect before it was
 * one:
 *
 * <ul>
 *   <li>a sub-level's blocks live in a plot region twenty million blocks away from where the
 *       structure is drawn, so the selection box cannot be used as a block range there: the box is
 *       mapped through the inverse pose to find the blocks, and each block's world position decides
 *       whether it is really inside the selection ({@link SubLevelPositions});</li>
 *   <li>the frame that turns a space's blocks into export coordinates must be composed in
 *       {@code double} and narrowed once, or every space keeps its own few blocks of error and two
 *       structures of one selection stop lining up ({@link SubLevelFrames});</li>
 *   <li>a block entity whose appearance lives in a Flywheel visual writes no vertices at all until
 *       the visual engine's answer is turned off for the pass ({@link FlywheelGate}).</li>
 * </ul>
 *
 * <p>The capture is written through the same output chain as the Export Wand: glTF + BIN + USDA,
 * one PNG per sprite, material sidecars and {@code report.json}. Every space (the ordinary world
 * and each sub-level) becomes one {@code Chunks} node named {@code sable/world} or
 * {@code sable/subNN_name}, holding both its block models and its block-entity geometry, so a ship
 * arrives as one selectable object. Block quads get the same per-sprite UV resolution, tint and
 * exact-coplanar layering as the ordinary export ({@link RegionQuadCapture}).
 *
 * <p>Known boundaries are listed in {@code README-SABLE-EXPORT.md} beside this package: no entity
 * (contraption/visual) export, no fluids, no neighbour face culling, and a block whose model has
 * no quads falls back to the skip log rather than to a bounding box.
 */
public final class RegionExport {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionExport.class);

    /** How many blocks one export may cover: 64 x 64 x 64. */
    public static final int MAX_BLOCKS = 64 * 64 * 64;

    /** How many {@code region: skip} lines one export may write. */
    private static final int SKIP_LINES = 20;

    /** How many {@code region-block} / {@code region-ber} lines one export may write, each. */
    private static final int DIAG_LINES = 40;

    /** How far a space's geometry may sit from its own blocks before it is called drift. */
    private static final float SPACE_TOLERANCE = 0.5f;

    /** Untinted: the colour multiplier a block quad gets when nothing colours it. */
    private static final float[] NO_TINT = {1.0f, 1.0f, 1.0f};

    /** Every direction plus the null one, which is where a model keeps its unculled quads. */
    private static final List<Direction> DIRECTIONS = directions();

    private RegionExport() {
    }

    /**
     * Runs the export, answering in chat and in the log rather than by throwing.
     *
     * @param source the command source, so a refusal reaches the player
     * @param name   file name stem of the export
     */
    public static int run(CommandSourceStack source, String name) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            LOGGER.info("region: refused reason=no-world");
            source.sendFailure(Component.literal(
                    "minetomesh sable: join a world first (no world loaded)"));
            return 0;
        }
        if (!minecraft.isSameThread()) {
            // Baked models, block-entity renderers and the atlas readback are client-thread state.
            LOGGER.info("region: refused reason=wrong-thread");
            source.sendFailure(Component.literal(
                    "minetomesh sable: run this from the client thread"));
            return 0;
        }

        RegionSelection.Box selection = RegionSelection.read();
        if (selection == null) {
            LOGGER.info("region: refused reason=no-selection tool-present={} class={}",
                    RegionSelection.available(), RegionSelection.SESSION_CLASS);
            source.sendFailure(Component.literal(
                    "minetomesh sable: no selection - select a region with the Sable blueprint tool"
                            + " (left click two corners) and run the command again"));
            return 0;
        }
        if (!selection.bothCorners()) {
            LOGGER.info("region: refused reason=one-corner aabb={}", selection.describe());
            source.sendFailure(Component.literal("minetomesh sable: only one corner is set ("
                    + selection.describe() + ") - click the opposite corner"));
            return 0;
        }
        long volume = selection.volume();
        if (volume <= 0 || volume > MAX_BLOCKS) {
            LOGGER.info("region: refused reason=too-large aabb={} volume={} limit={}",
                    selection.describe(), volume, MAX_BLOCKS);
            source.sendFailure(Component.literal("minetomesh sable: selection is too large ("
                    + volume + " blocks > limit " + MAX_BLOCKS + ")"));
            return 0;
        }
        ExportName exportName;
        try {
            exportName = ExportName.parse(name);
        } catch (IllegalArgumentException exception) {
            LOGGER.info("region: refused reason=unsafe-name name={} ({})", name,
                    exception.getMessage());
            source.sendFailure(Component.literal("minetomesh sable: " + exception.getMessage()));
            return 0;
        }

        try {
            Result result = export(minecraft, exportName, selection);
            source.sendSuccess(() -> Component.literal("minetomesh sable: " + result.output()
                    + " (" + result.faces() + " quads, " + result.blocks() + " blocks, "
                    + result.spaces() + " spaces)"), false);
            return 1;
        } catch (Throwable throwable) {
            LOGGER.error("region: export failed name={}", exportName.value(), throwable);
            source.sendFailure(Component.literal("minetomesh sable failed: " + throwable));
            return 0;
        }
    }

    /** What one export produced, for the command's answer. */
    public record Result(Path output, int faces, int blocks, int spaces) {
    }

    // ------------------------------------------------------------------ the export

    private static Result export(Minecraft minecraft, ExportName name, RegionSelection.Box selection)
            throws IOException {
        Level level = Objects.requireNonNull(minecraft.level, "level");
        long startGameTime = level.getGameTime();
        AABB box = selection.aabb();
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = Math.max(minX, (int) Math.ceil(box.maxX) - 1);
        int maxY = Math.max(minY, (int) Math.ceil(box.maxY) - 1);
        int maxZ = Math.max(minZ, (int) Math.ceil(box.maxZ) - 1);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_BLOCKS) {
            throw new IOException("region too large: " + volume + " blocks, limit " + MAX_BLOCKS);
        }

        // Everything is written around the selection's own corner: a world-coordinate mesh a few
        // thousand blocks from the origin is unusable in Blender, and the numbers lose precision.
        Vec3 origin = new Vec3(minX, minY, minZ);
        BlockPos originPos = new BlockPos(minX, minY, minZ);
        Tally tally = new Tally();

        List<Space> spaces = new ArrayList<>();
        Space world = new Space("world", "world", 0, SubLevelFrames.world(originPos), originPos,
                level);
        scanWorld(level, minX, minY, minZ, maxX, maxY, maxZ, world, tally);
        spaces.add(world);
        scanSubLevels(level, box, origin, spaces, tally);

        LOGGER.info("region: selection source={} reader={} aabb=({}, {}, {})..({}, {}, {})"
                        + " volume={} blocks={}", RegionSelection.SESSION_CLASS, selection.detail(),
                minX, minY, minZ, maxX, maxY, maxZ, volume, tally.examined);

        TextureRegistry textures = new TextureRegistry();
        List<Diagnostic> diagnostics = new ArrayList<>();
        RegionQuadCapture quadCapture = new RegionQuadCapture(
                new SpriteTextureExtractor(minecraft.getResourceManager()),
                new AtlasSpriteResolver(atlasId -> minecraft.getModelManager().getAtlas(atlasId)),
                textures);
        Function<RenderTypeDescriptor, MaterialKey> materialResolver =
                DefaultExportPipeline.resourceMaterialResolver(minecraft, textures, diagnostics);

        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        BlockEntityRenderDispatcher blockEntityRenderer = minecraft.getBlockEntityRenderDispatcher();
        GeometryAdjustmentStats adjustments = GeometryAdjustmentStats.ZERO;

        for (Space space : spaces) {
            logSpace(space);
            adjustments = adjustments.plus(
                    modelPass(space, blockRenderer, quadCapture, diagnostics, tally));
        }
        // The gate is taken around the whole block-entity pass rather than per renderer call: the
        // backend is one static field, and every renderer in the pass has to see the same answer.
        boolean gated = FlywheelGate.engage();
        try {
            for (Space space : spaces) {
                berPass(space, blockEntityRenderer, materialResolver, diagnostics, tally);
            }
        } finally {
            // Released before anything else: the visual engine's own answer has to be true again
            // for the rest of the frame, whatever a renderer did.
            FlywheelGate.release(gated);
        }

        List<CapturedNode> nodes = new ArrayList<>();
        for (Space space : spaces) {
            PrimitiveAccumulator.SealResult sealed = space.accumulator.seal();
            diagnostics.addAll(sealed.diagnostics());
            space.sealed = sealed.primitives();
            if (!sealed.primitives().isEmpty()) {
                nodes.add(new CapturedNode(
                        RegionScene.nodeName(space.index, space.name),
                        CapturedNode.Kind.CHUNK,
                        sealed.primitives(),
                        RegionScene.nodeExtras(space.index, space.name)));
            }
        }
        if (nodes.isEmpty()) {
            throw new IOException("nothing to export: blocks=" + tally.examined
                    + ", block entities=" + tally.blockEntities + ", selection=" + minX + ","
                    + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ);
        }
        checkSpaces(spaces);

        if (tally.fluids > 0) {
            diagnostics.add(diagnostic(Diagnostic.Severity.INFO, "SABLE_FLUIDS_SKIPPED",
                    "sable", "", "", tally.fluids + " fluid block(s) in the selection were not"
                            + " exported; the Sable region export carries no fluid geometry"));
        }
        if (tally.skipped > 0) {
            diagnostics.add(diagnostic(Diagnostic.Severity.WARNING, "SABLE_BLOCKS_SKIPPED",
                    "sable", "", "", tally.skipped + " block(s) contributed no geometry: "
                            + tally.reasons));
        }

        Set<MaterialKey> materials = new LinkedHashSet<>();
        long triangles = 0;
        for (CapturedNode node : nodes) {
            node.primitives().forEach(primitive -> materials.add(primitive.material()));
            triangles += RegionScene.triangleCount(node.primitives());
        }
        BatchCounters counters = new BatchCounters(
                tally.examined, tally.produced, 0, tally.berDrawn, 0,
                materials.size(), textures.size(), triangles, 0);
        ChunkBatch batch = new ChunkBatch(nodes, diagnostics, counters, adjustments);

        long warnings = DefaultExportPipeline.warningCount(diagnostics);
        int[] min = {minX, minY, minZ};
        int[] max = {maxX, maxY, maxZ};
        ExportReport report = new ExportReport(
                warnings == 0 ? "completed" : "completed_with_warnings",
                "sable_region_snapshot",
                level.dimension().location().toString(),
                min, max, min, volume,
                startGameTime, level.getGameTime(),
                counters, adjustments, List.of(), diagnostics, Map.of());

        Path exportRoot = minecraft.gameDirectory.toPath().resolve("minetomesh-exports");
        Path published = RegionSceneWriter.write(
                exportRoot, name, rootExtras(minecraft, level, min, max, spaces),
                batch, textures, report);

        LOGGER.info("region: baked blocks={} examined={} quads={} entities=0 ber={} materials={}"
                        + " textures={} triangles={} skipped={} fluids={} spaces={} reasons={}",
                tally.produced, tally.examined, tally.quads, tally.berDrawn, materials.size(),
                textures.size(), triangles, tally.skipped, tally.fluids, spaces.size(),
                tally.reasons);
        return new Result(published, tally.quads, tally.produced, spaces.size());
    }

    private static Map<String, Object> rootExtras(
            Minecraft minecraft, Level level, int[] min, int[] max, List<Space> spaces) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("minecraftVersion", "1.21.1");
        extras.put("neoForgeVersion", loadedModVersion("neoforge"));
        extras.put("exporterVersion", MineToMesh.VERSION);
        extras.put("exportSource", "sable_region");
        extras.put("dimension", level.dimension().location().toString());
        extras.put("selectionMin", List.of(min[0], min[1], min[2]));
        extras.put("selectionMax", List.of(max[0], max[1], max[2]));
        extras.put("origin", List.of(min[0], min[1], min[2]));
        extras.put("coordinateSpaces", spaces.stream()
                .map(space -> RegionScene.objectName(space.index, space.name)).toList());
        extras.put("activeResourcePacks", List.copyOf(
                minecraft.getResourcePackRepository().getSelectedIds()));
        extras.put("loadedMods", ModList.get().getMods().stream()
                .map(info -> info.getModId() + "@" + info.getVersion())
                .sorted()
                .toList());
        extras.put("snapshotMode", "sable_region_snapshot");
        extras.put("formats", List.of("gltf", "usda"));
        extras.put("sourceTopologyPreservedInUsda", true);
        return extras;
    }

    private static String loadedModVersion(String modId) {
        return ModList.get().getMods().stream()
                .filter(info -> info.getModId().equals(modId))
                .map(info -> info.getVersion().toString())
                .findFirst()
                .orElse("unknown");
    }

    private static Diagnostic diagnostic(Diagnostic.Severity severity, String code, String objectId,
                                         String rendererClass, String exceptionType, String message) {
        return new Diagnostic(severity, code, objectId, Optional.empty(), rendererClass,
                exceptionType, message);
    }

    /** One coordinate space the export draws from: the world, or one sub-level's storage. */
    private static final class Space {
        final String label;
        /** The structure's own name, for the object line; {@code world} for the ordinary world. */
        final String name;
        /** Space index; 0 is the world, sub-levels follow. */
        final int index;
        /** Base-relative coordinates of this space to export coordinates. */
        final SubLevelFrames.Frame frame;
        /** The corner subtracted from every block position before the frame. */
        final BlockPos base;
        /** The level the blocks are read from. */
        final Level level;
        final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        final Map<BlockPos, BlockEntity> blockEntities = new LinkedHashMap<>();
        /** Blocks the model pass drew nothing for; the renderer pass may still draw them. */
        final List<Pending> pending = new ArrayList<>();
        /** Whether any renderer in this space wrote a vertex. */
        int berVertices;
        /** Collects the block and block-entity geometry of this space, material by material. */
        final PrimitiveAccumulator accumulator;
        /** The sealed geometry of this space, set once both passes are done. */
        List<PrimitiveData> sealed = List.of();

        Space(String label, String name, int index, SubLevelFrames.Frame frame, BlockPos base,
              Level level) {
            this.label = label;
            this.name = name == null || name.isBlank() ? label : name;
            this.index = index;
            this.frame = frame == null ? SubLevelFrames.world(base) : frame;
            this.base = base;
            this.level = level;
            this.accumulator = new PrimitiveAccumulator(RegionScene.nodeName(index, this.name));
        }

        BlockPos relative(BlockPos pos) {
            return pos.subtract(base);
        }

        String object() {
            // The object name is what a Blender outliner shows, so it carries the structure's own
            // name once rather than the log label (which repeats the index as `sub1:name`).
            return RegionScene.objectName(index, name);
        }
    }

    /** A block the model pass drew nothing for, and why. */
    private record Pending(BlockPos pos, String block, String reason) {
    }

    // ------------------------------------------------------------------ scanning

    /** The world-space blocks inside the selection, read from the client level as it stands. */
    private static void scanWorld(Level level, int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ, Space space, Tally tally) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state;
                    try {
                        state = level.getBlockState(cursor);
                    } catch (Throwable throwable) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    // Water is a block with no geometry of its own; a selection beside a river
                    // would otherwise fill the skip log with it.
                    if (!state.getFluidState().isEmpty()) {
                        tally.fluids++;
                        continue;
                    }
                    BlockPos pos = cursor.immutable();
                    space.states.put(pos, state);
                    tally.examined++;
                    try {
                        BlockEntity blockEntity = level.getBlockEntity(pos);
                        if (blockEntity != null) {
                            space.blockEntities.put(pos, blockEntity);
                            tally.blockEntities++;
                        }
                    } catch (Throwable ignored) {
                        // a block entity that cannot be read contributes its model only
                    }
                }
            }
        }
    }

    /**
     * Every sub-level that intersects the selection, mapped into its own storage coordinates.
     *
     * <p>The box is mapped through the inverse pose and the resulting range is what gets walked,
     * because the storage range and the world range have nothing to do with each other. Each
     * block's own world position then decides whether it is inside the selection, which is what
     * makes a rotated or scaled sub-level come out right.
     */
    private static void scanSubLevels(Level level, AABB box, Vec3 origin, List<Space> spaces,
                                      Tally tally) {
        List<SubLevelPositions.Handle> handles = SubLevelPositions.intersecting(level, box);
        if (handles.isEmpty()) {
            LOGGER.info("region: sub-levels=0 sable={} (no Sable structure intersects the selection)",
                    SubLevelPositions.available());
            return;
        }
        int index = 1;
        for (SubLevelPositions.Handle handle : handles) {
            try {
                Space space = scanOneSubLevel(level, box, origin, handle, index, tally);
                if (space != null) {
                    spaces.add(space);
                    index++;
                }
            } catch (Throwable throwable) {
                LOGGER.warn("region: sub-level skipped ({}): {}", handle.name(), throwable.toString());
            }
        }
    }

    private static Space scanOneSubLevel(Level level, AABB box, Vec3 origin,
                                         SubLevelPositions.Handle handle, int index, Tally tally) {
        // The box as seen from inside the sub-level. Eight corners are enough: the pose is affine,
        // so the image of an axis-aligned box is a parallelepiped whose bounds are the bounds of
        // its image corners.
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? box.minX : box.maxX;
            double y = (corner & 2) == 0 ? box.minY : box.maxY;
            double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
            Vector3d local = handle.toLocal(x, y, z);
            if (!Double.isFinite(local.x) || !Double.isFinite(local.y) || !Double.isFinite(local.z)) {
                return null;
            }
            minX = Math.min(minX, local.x);
            minY = Math.min(minY, local.y);
            minZ = Math.min(minZ, local.z);
            maxX = Math.max(maxX, local.x);
            maxY = Math.max(maxY, local.y);
            maxZ = Math.max(maxZ, local.z);
        }
        BlockPos base = new BlockPos((int) Math.floor(minX), (int) Math.floor(minY),
                (int) Math.floor(minZ));
        SubLevelFrames.Frame frame = SubLevelFrames.compose(
                new Vector3d(origin.x, origin.y, origin.z), handle.pose(), base);
        String label = "sub" + index + ":" + handle.name();
        Space space = new Space(label, handle.name(), index, frame, base, level);

        int fromX = (int) Math.floor(minX) - 1;
        int fromY = (int) Math.floor(minY) - 1;
        int fromZ = (int) Math.floor(minZ) - 1;
        int toX = (int) Math.ceil(maxX) + 1;
        int toY = (int) Math.ceil(maxY) + 1;
        int toZ = (int) Math.ceil(maxZ) + 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = fromZ; z <= toZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state;
                    try {
                        // The block may be in the plot level or in the root level; the helper
                        // asks every level that could hold it.
                        state = SubLevelPositions.blockState(level, cursor);
                    } catch (Throwable throwable) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    Vector3d centre = handle.toWorld(x + 0.5, y + 0.5, z + 0.5);
                    if (!box.contains(centre.x, centre.y, centre.z)) {
                        continue;
                    }
                    if (!state.getFluidState().isEmpty()) {
                        tally.fluids++;
                        continue;
                    }
                    BlockPos pos = cursor.immutable();
                    space.states.put(pos, state);
                    tally.examined++;
                    try {
                        BlockEntity blockEntity = SubLevelPositions.blockEntity(level, pos);
                        if (blockEntity != null) {
                            space.blockEntities.put(pos, blockEntity);
                            tally.blockEntities++;
                        }
                    } catch (Throwable ignored) {
                        // model only, as above
                    }
                }
            }
        }
        return space.states.isEmpty() ? null : space;
    }

    // ------------------------------------------------------------------ geometry

    /**
     * The model pass for one space: every block's own baked model, resolved per quad to its real
     * sprite, with one verdict per block. A block's quads are layered as a group so exactly
     * coincident faces (a powered rail's two surfaces, a grass block's base and overlay) are kept
     * and offset instead of flickering.
     *
     * @return the geometry adjustments the layering made
     */
    private static GeometryAdjustmentStats modelPass(
            Space space,
            BlockRenderDispatcher dispatcher,
            RegionQuadCapture capture,
            List<Diagnostic> diagnostics,
            Tally tally) {
        GeometryAdjustmentStats adjustments = GeometryAdjustmentStats.ZERO;
        Set<String> inspectedRenderTypes = new HashSet<>();
        RandomSource random = RandomSource.create();
        for (Map.Entry<BlockPos, BlockState> entry : space.states.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            String block = blockId(state);
            if (state.getRenderShape() != RenderShape.MODEL) {
                // The geometry lives in a renderer, which the block-entity pass runs at the end.
                space.pending.add(new Pending(pos, block, "unsupported"));
                continue;
            }
            BakedModel model;
            try {
                model = dispatcher.getBlockModel(state);
            } catch (Throwable throwable) {
                space.pending.add(new Pending(pos, block, "exception"));
                continue;
            }
            if (model == null) {
                space.pending.add(new Pending(pos, block, "no-model"));
                continue;
            }
            IBakedModelExtension extension =
                    model instanceof IBakedModelExtension candidate ? candidate : null;
            ModelData modelData = ModelData.EMPTY;
            if (extension != null) {
                // A plot block's model data lives on the level that holds the block entity, which
                // is not always the level this space was opened with.
                BlockEntity blockEntity = space.blockEntities.get(pos);
                Level dataLevel = blockEntity == null || blockEntity.getLevel() == null
                        ? space.level : blockEntity.getLevel();
                try {
                    modelData = extension.getModelData(dataLevel, pos, state, ModelData.EMPTY);
                } catch (Throwable throwable) {
                    modelData = ModelData.EMPTY;
                }
            }
            List<RenderType> layers = new ArrayList<>();
            if (extension != null) {
                try {
                    for (RenderType layer : extension.getRenderTypes(state, random, modelData)) {
                        layers.add(layer);
                    }
                } catch (Throwable ignored) {
                    // a model that cannot answer keeps the state's own sheet below
                }
            }
            if (layers.isEmpty()) {
                RenderType single = null;
                try {
                    single = ItemBlockRenderTypes.getChunkRenderType(state);
                } catch (Throwable ignored) {
                    // no sheet to ask for
                }
                layers.add(single == null ? RenderType.solid() : single);
            }

            // Models such as flowers and bamboo are drawn displaced by a position-derived offset.
            Vec3 offset = Vec3.ZERO;
            try {
                offset = state.getOffset(space.level, pos);
            } catch (Throwable ignored) {
                // an offset that cannot be read leaves the block where its own position puts it
            }
            // `frame . T(block - base)`: the frame first, so the pose a quad goes through is the
            // product and never the float-composed translation on its own.
            PoseStack pose = new PoseStack();
            pose.mulPose(space.frame.render());
            BlockPos relative = space.relative(pos);
            pose.translate(relative.getX() + offset.x, relative.getY() + offset.y,
                    relative.getZ() + offset.z);
            PoseStack.Pose last = pose.last();

            List<RegionQuadCapture.Captured> pending = new ArrayList<>();
            boolean failed = false;
            long seed = state.getSeed(pos);
            for (RenderType layer : layers) {
                RenderTypeInspector.Inspection inspection = RenderTypeInspector.inspect(layer);
                RenderTypeDescriptor descriptor = inspection.descriptor();
                if (inspectedRenderTypes.add(descriptor.name())) {
                    diagnostics.addAll(inspection.diagnostics());
                }
                if (descriptor.discard()) {
                    continue;
                }
                for (Direction direction : DIRECTIONS) {
                    RandomSource quadRandom = RandomSource.create();
                    quadRandom.setSeed(seed);
                    List<BakedQuad> quads;
                    try {
                        quads = extension == null
                                ? model.getQuads(state, direction, quadRandom)
                                : extension.getQuads(state, direction, quadRandom, modelData, layer);
                    } catch (Throwable throwable) {
                        continue;
                    }
                    if (quads == null) {
                        continue;
                    }
                    for (BakedQuad quad : quads) {
                        if (quad == null) {
                            continue;
                        }
                        try {
                            float[] tint = tint(space, state, pos, quad, tally);
                            pending.add(capture.capture(last, quad, tint[0], tint[1], tint[2],
                                    descriptor, block, diagnostics));
                        } catch (Throwable throwable) {
                            failed = true;
                        }
                    }
                }
            }

            if (!pending.isEmpty()) {
                CoplanarQuadLayering.Result layered = CoplanarQuadLayering.apply(
                        pending.stream().map(RegionQuadCapture.Captured::vertices).toList());
                for (int index = 0; index < pending.size(); index++) {
                    RegionQuadCapture.Captured quad = pending.get(index);
                    space.accumulator.append(
                            quad.material(), quad.mode(), layered.quads().get(index));
                }
                CoplanarQuadLayering.Statistics layering = layered.statistics();
                adjustments = adjustments.plus(GeometryAdjustmentStats.forBlock(
                        block, layering.coplanarGroups(), layering.offsetFaces(),
                        layering.maxLayers()));
                if (layering.invalidNormals() > 0) {
                    diagnostics.add(diagnostic(Diagnostic.Severity.WARNING,
                            "COPLANAR_FACE_NORMAL_INVALID", block, "", "",
                            "Could not offset " + layering.invalidNormals()
                                    + " coincident face layer(s) because their normals were"
                                    + " degenerate"));
                }
                tally.quads += pending.size();
                tally.produced++;
            } else {
                space.pending.add(new Pending(pos, block, failed ? "exception" : "no-model"));
            }
            if (tally.wantBlockLine() || tally.firstBlockKind(block)) {
                LOGGER.info("region-block: id={} state={} shape={} quads={} be={}",
                        block, state.toString(), !pending.isEmpty() ? "baked-quads"
                                : "entity-or-empty", pending.size(),
                        space.blockEntities.containsKey(pos));
            }
        }
        return adjustments;
    }

    /**
     * The block-colour multiplier of one quad, as {@code r, g, b} in {@code 0..1}.
     *
     * <p>A sub-level's blocks are asked in their plot coordinates, which have no biome of their
     * own; a colour provider that cannot answer there leaves the quad white rather than failing
     * the block.
     */
    private static float[] tint(Space space, BlockState state, BlockPos pos, BakedQuad quad,
                                Tally tally) {
        if (!quad.isTinted()) {
            return NO_TINT;
        }
        try {
            int tint = Minecraft.getInstance().getBlockColors()
                    .getColor(state, space.level, pos, quad.getTintIndex());
            if (tint != -1) {
                return new float[] {
                    (tint >> 16 & 0xFF) / 255.0f,
                    (tint >> 8 & 0xFF) / 255.0f,
                    (tint & 0xFF) / 255.0f};
            }
        } catch (Throwable throwable) {
            tally.tintFailures++;
        }
        return NO_TINT;
    }

    /**
     * The block-entity pass for one space.
     *
     * <p>The renderer is handed an empty pose: it writes relative to its own block and follows
     * whatever pose it is given, and the buffers add {@code frame . T(block - base)}. Giving it
     * both would count the placement twice - the mistake that made sub-level geometry span four
     * units instead of twenty-one.
     *
     * <p>It is called directly rather than through
     * {@link BlockEntityRenderDispatcher#render}, because the dispatcher asks
     * {@code shouldRender(blockEntity, camera)} first and the default answer is "within view
     * distance of the camera". A sub-level's blocks sit twenty million blocks from where they are
     * drawn, so every one of them would be culled - and so would half of a large world selection.
     * The dispatcher is still used for the renderer lookup, which is a plain type map.
     */
    private static void berPass(
            Space space,
            BlockEntityRenderDispatcher dispatcher,
            Function<RenderTypeDescriptor, MaterialKey> materialResolver,
            List<Diagnostic> diagnostics,
            Tally tally) {
        if (space.blockEntities.isEmpty()) {
            resolvePending(space, false, tally);
            return;
        }
        float partialTick = partialTick();
        PoseStack pose = new PoseStack();
        for (Map.Entry<BlockPos, BlockEntity> entry : space.blockEntities.entrySet()) {
            BlockEntity blockEntity = entry.getValue();
            String berId = blockId(blockEntity.getBlockState());
            BlockEntityRenderer<BlockEntity> renderer = null;
            try {
                renderer = dispatcher.getRenderer(blockEntity);
            } catch (Throwable throwable) {
                LOGGER.info("region-ber: id={} reason=lookup-failed ({})",
                        berId, throwable.getClass().getSimpleName());
            }
            if (renderer == null) {
                if (tally.wantBerLine()) {
                    LOGGER.info("region-ber: id={} ber=- ok=false quads=0 reason=no-renderer",
                            berId);
                }
                continue;
            }
            BlockPos relative = space.relative(entry.getKey());
            Matrix4f placement = new Matrix4f(space.frame.render())
                    .translate(relative.getX(), relative.getY(), relative.getZ());
            RegionBerBuffers source = new RegionBerBuffers(placement);
            try {
                pose.pushPose();
                // Full brightness rather than 0, and NO_OVERLAY rather than 0: zero is a real
                // overlay value (a hurt flash), not "none".
                renderer.render(blockEntity, partialTick, pose, source,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                pose.popPose();
                int written = source.vertices();
                space.berVertices += written;
                if (written > 0) {
                    source.finishInto(space.accumulator, materialResolver, diagnostics);
                    tally.berDrawn++;
                } else if (tally.wantBerLine()) {
                    LOGGER.info("region-ber: id={} ber={} ok=false quads=0 reason=drew-nothing",
                            berId, renderer.getClass().getSimpleName());
                }
            } catch (Throwable throwable) {
                try {
                    pose.popPose();
                } catch (Throwable ignored) {
                    // the stack is rebuilt per block anyway
                }
                diagnostics.add(diagnostic(Diagnostic.Severity.WARNING,
                        "BLOCK_ENTITY_CAPTURE_FAILED", berId, renderer.getClass().getName(),
                        throwable.getClass().getName(),
                        throwable.getMessage() == null
                                ? "Block entity capture failed" : throwable.getMessage()));
                if (tally.wantBerLine()) {
                    LOGGER.info("region-ber: id={} ber={} ok=false reason={}: {}",
                            berId, renderer.getClass().getSimpleName(),
                            throwable.getClass().getSimpleName(), throwable.getMessage());
                }
            }
        }
        resolvePending(space, space.berVertices > 0, tally);
    }

    /**
     * Settles the blocks the model pass drew nothing for.
     *
     * <p>A block whose shape lives in its renderer emits no model geometry and is drawn by the
     * block-entity pass, so reporting it as skipped would say the export lost a part that is in
     * the file.
     */
    private static void resolvePending(Space space, boolean rendererDrew, Tally tally) {
        for (Pending pending : space.pending) {
            if (rendererDrew && space.blockEntities.containsKey(pending.pos())) {
                tally.produced++;
            } else {
                tally.skip(pending.block(), pending.reason());
            }
        }
        space.pending.clear();
    }

    /**
     * The {@code region-space-check} lines: where a space's geometry landed, against where its own
     * blocks say it should land.
     *
     * <p>Both boxes are in export coordinates and the verdict is the distance between their
     * centres. A space whose frame translation is a few blocks out reads that error here even
     * though its own parts agree with each other - which is the multi-structure fault exactly.
     */
    private static void checkSpaces(List<Space> spaces) {
        for (Space space : spaces) {
            try {
                float[] blockBox = space.frame.blockBounds(space.states.keySet(), space.base);
                float[] vertexBox = RegionScene.vertexBounds(space.sealed);
                if (blockBox == null && vertexBox == null) {
                    continue;
                }
                float worst = 0f;
                if (blockBox != null && vertexBox != null) {
                    for (int axis = 0; axis < 3; axis++) {
                        worst = Math.max(worst,
                                Math.abs(centre(vertexBox, axis) - centre(blockBox, axis)));
                    }
                }
                LOGGER.info("region-space-check: space={} name={} frame-t=({}) drift=({})"
                                + " blocks={} verts-bbox=({})..({}) block-bbox=({})..({}) verdict={}",
                        space.index, space.label, triple(space.frame.render().m30(),
                                space.frame.render().m31(), space.frame.render().m32()),
                        triple(space.frame.drift()), space.states.size(),
                        triple(vertexBox, 0), triple(vertexBox, 3),
                        triple(blockBox, 0), triple(blockBox, 3),
                        blockBox == null ? "no-reference"
                                : vertexBox == null ? "no-geometry"
                                : worst <= SPACE_TOLERANCE ? "ok" : "drift");
            } catch (Throwable throwable) {
                LOGGER.info("region-space-check: space={} name={} verdict=unavailable ({})",
                        space.index, space.label, throwable.getClass().getSimpleName());
            }
        }
    }

    private static void logSpace(Space space) {
        LOGGER.info("region: space index={} name={} blocks={} block-entities={} frame-t=({}, {}, {})",
                space.index, space.label, space.states.size(), space.blockEntities.size(),
                format(space.frame.render().m30()), format(space.frame.render().m31()),
                format(space.frame.render().m32()));
    }

    // ------------------------------------------------------------------ small helpers

    private static List<Direction> directions() {
        List<Direction> all = new ArrayList<>(Arrays.asList(Direction.values()));
        all.add(null);
        return java.util.Collections.unmodifiableList(all);
    }

    private static float partialTick() {
        try {
            return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        } catch (Throwable throwable) {
            return 0.0f;
        }
    }

    private static String blockId(BlockState state) {
        try {
            return String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        } catch (Throwable throwable) {
            return "?";
        }
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String triple(float a, float b, float c) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", a, b, c);
    }

    private static String triple(float[] values) {
        return values == null || values.length < 3 ? "-"
                : triple(values[0], values[1], values[2]);
    }

    private static String triple(float[] values, int from) {
        if (values == null || from < 0 || from + 2 >= values.length) {
            return "-";
        }
        return triple(values[from], values[from + 1], values[from + 2]);
    }

    private static float centre(float[] box, int axis) {
        return (box[axis] + box[axis + 3]) * 0.5f;
    }

    /** Counts one export's blocks, and the reasons any of them contributed nothing. */
    private static final class Tally {
        int examined;
        int produced;
        int skipped;
        int fluids;
        int blockEntities;
        int berDrawn;
        int quads;
        int tintFailures;
        final Map<String, Integer> reasons = new LinkedHashMap<>();
        private int lines;
        private int blockLines;
        private int berLines;
        private final java.util.Set<String> blockKinds = new java.util.LinkedHashSet<>();
        private final java.util.Set<String> berKinds = new java.util.LinkedHashSet<>();

        boolean firstBlockKind(String id) {
            return firstKind(id, blockKinds);
        }

        boolean wantBlockLine() {
            if (blockLines >= DIAG_LINES) {
                return false;
            }
            blockLines++;
            return true;
        }

        boolean wantBerLine() {
            if (berLines >= DIAG_LINES) {
                return false;
            }
            berLines++;
            return true;
        }

        private boolean firstKind(String id, java.util.Set<String> seen) {
            if (id == null || id.isEmpty() || seen.size() >= 128) {
                return false;
            }
            return seen.add(id);
        }

        void skip(String block, String reason) {
            skipped++;
            reasons.merge(reason, 1, Integer::sum);
            if (lines < SKIP_LINES) {
                lines++;
                LOGGER.info("region: skip block={} reason={}", block, reason);
            }
        }
    }
}
