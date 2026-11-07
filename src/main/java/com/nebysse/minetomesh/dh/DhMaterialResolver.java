package com.nebysse.minetomesh.dh;

import com.nebysse.minetomesh.scene.ColorRgba;
import com.nebysse.minetomesh.scene.MaterialKey;
import com.nebysse.minetomesh.scene.TextureKey;
import com.nebysse.minetomesh.texture.TextureImage;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import java.awt.Color;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves materials and colors for Distant Horizons LOD datapoints.
 */
public final class DhMaterialResolver {
    public static final TextureKey GENERIC_LOD_TEXTURE = new TextureKey(
            TextureKey.Kind.DYNAMIC,
            "minetomesh:dh_lod_generic",
            "textures/minetomesh/generated/dh_lod_generic.png");

    public static final MaterialKey GENERIC_LOD_MATERIAL = new MaterialKey(
            GENERIC_LOD_TEXTURE,
            MaterialKey.AlphaMode.OPAQUE,
            Optional.empty(),
            false,
            false,
            MaterialKey.BlendSemantic.STANDARD,
            MaterialKey.SamplerMode.NEAREST);

    private static final Map<String, MaterialKey> MATERIAL_CACHE = new ConcurrentHashMap<>();

    private DhMaterialResolver() {
    }

    public static TextureImage genericLodTextureImage() {
        return new TextureImage(
                1,
                1,
                new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255},
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static MaterialKey resolveMaterial(IDhApiBlockStateWrapper blockState) {
        if (blockState == null) {
            return GENERIC_LOD_MATERIAL;
        }

        try {
            String label = null;
            try {
                label = blockState.getSerialString();
            } catch (Throwable ignored) {
            }

            if (label == null || label.isBlank()) {
                try {
                    byte matId = blockState.getMaterialId();
                    EDhApiBlockMaterial mat = EDhApiBlockMaterial.getFromIndex(matId);
                    if (mat != null) {
                        label = "dh_mat_" + mat.name().toLowerCase();
                    }
                } catch (Throwable ignored) {
                }
            }

            if (label == null || label.isBlank()) {
                return GENERIC_LOD_MATERIAL;
            }

            String sanitized = sanitize(label);
            return MATERIAL_CACHE.computeIfAbsent(sanitized, key -> {
                TextureKey texture = new TextureKey(
                        TextureKey.Kind.DYNAMIC,
                        "minetomesh:dh_lod_" + key,
                        "textures/minetomesh/generated/dh_lod_" + key + ".png");
                return new MaterialKey(
                        texture,
                        MaterialKey.AlphaMode.OPAQUE,
                        Optional.empty(),
                        false,
                        false,
                        MaterialKey.BlendSemantic.STANDARD,
                        MaterialKey.SamplerMode.NEAREST);
            });
        } catch (Throwable ignored) {
            return GENERIC_LOD_MATERIAL;
        }
    }

    public static ColorRgba resolveColor(
            IDhApiLevelWrapper level,
            IDhApiBlockStateWrapper blockState,
            IDhApiBiomeWrapper biome,
            int worldX,
            int worldY,
            int worldZ) {
        try {
            if (level != null && blockState != null) {
                try {
                    DhApiResult<Color> colorResult = level.getBlockColorPreApi(
                            blockState, biome, worldX, worldY, worldZ, null);
                    if (colorResult != null && colorResult.success && colorResult.payload != null) {
                        Color c = colorResult.payload;
                        return new ColorRgba(c.getRed(), c.getGreen(), c.getBlue(), 255);
                    }
                } catch (Throwable ignored) {
                }
            }

            // Fallback colors based on material enum if available
            if (blockState != null) {
                try {
                    byte matId = blockState.getMaterialId();
                    EDhApiBlockMaterial mat = EDhApiBlockMaterial.getFromIndex(matId);
                    if (mat != null) {
                        return switch (mat) {
                            case GRASS -> new ColorRgba(120, 178, 86, 255);
                            case LEAVES -> new ColorRgba(89, 140, 62, 255);
                            case DIRT -> new ColorRgba(134, 96, 67, 255);
                            case STONE, DEEPSLATE -> new ColorRgba(125, 125, 125, 255);
                            case WOOD -> new ColorRgba(150, 116, 65, 255);
                            case WATER -> new ColorRgba(63, 118, 228, 255);
                            case LAVA -> new ColorRgba(211, 91, 19, 255);
                            case SAND -> new ColorRgba(219, 211, 160, 255);
                            case SNOW -> new ColorRgba(240, 245, 245, 255);
                            case NETHER_STONE -> new ColorRgba(115, 41, 41, 255);
                            case TERRACOTTA -> new ColorRgba(152, 94, 67, 255);
                            default -> ColorRgba.WHITE;
                        };
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return ColorRgba.WHITE;
    }

    private static String sanitize(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
    }
}
