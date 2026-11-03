package com.nebysse.minetomesh.sable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nebysse.minetomesh.capture.CapturingVertexConsumer;
import com.nebysse.minetomesh.capture.RenderTypeDescriptor;
import com.nebysse.minetomesh.material.MaterialResolver;
import com.nebysse.minetomesh.scene.Diagnostic;
import com.nebysse.minetomesh.scene.MaterialKey;
import com.nebysse.minetomesh.scene.PrimitiveMode;
import com.nebysse.minetomesh.scene.Vec2f;
import com.nebysse.minetomesh.scene.Vertex;
import com.nebysse.minetomesh.texture.AtlasSpriteIndex;
import com.nebysse.minetomesh.texture.AtlasSpriteResolver;
import com.nebysse.minetomesh.texture.SpriteTextureExtractor;
import com.nebysse.minetomesh.texture.TextureRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Turns one {@link BakedQuad} into scene vertices with a per-sprite texture.
 *
 * <p>This is the same resolution the ordinary export applies to block quads: the real sprite is
 * found from the quad's own atlas UVs, that sprite is extracted as its own PNG, and the UVs are
 * re-normalised into it. The Sable path used to keep atlas-space UVs because OBJ had nowhere to
 * put a per-face texture; glTF and USDA do, so every quad now points at its own sprite image and
 * the whole atlas no longer has to be copied out of the GPU.
 *
 * <p>The pose is applied by the vertex consumer, so the returned positions are already in export
 * coordinates, and the normals have been rotated by the same pose.
 */
final class RegionQuadCapture {
    private final SpriteTextureExtractor spriteExtractor;
    private final AtlasSpriteResolver atlasSpriteResolver;
    private final TextureRegistry textureRegistry;
    private final Map<TextureAtlasSprite, SpriteTextureExtractor.Extraction> spriteCache =
            new IdentityHashMap<>();
    private final Set<String> reported = new HashSet<>();

    RegionQuadCapture(
            SpriteTextureExtractor spriteExtractor,
            AtlasSpriteResolver atlasSpriteResolver,
            TextureRegistry textureRegistry) {
        this.spriteExtractor = Objects.requireNonNull(spriteExtractor, "spriteExtractor");
        this.atlasSpriteResolver = Objects.requireNonNull(atlasSpriteResolver, "atlasSpriteResolver");
        this.textureRegistry = Objects.requireNonNull(textureRegistry, "textureRegistry");
    }

    /** One captured quad: its material, how its vertices are read, and the vertices themselves. */
    record Captured(MaterialKey material, PrimitiveMode mode, List<Vertex> vertices) {
    }

    Captured capture(
            PoseStack.Pose pose,
            BakedQuad quad,
            float red,
            float green,
            float blue,
            RenderTypeDescriptor descriptor,
            String objectId,
            List<Diagnostic> diagnostics) throws IOException {
        CapturingVertexConsumer consumer = new CapturingVertexConsumer();
        consumer.putBulkData(
                pose,
                quad,
                new float[] {1.0F, 1.0F, 1.0F, 1.0F},
                red,
                green,
                blue,
                1.0F,
                new int[] {
                    LightTexture.FULL_BRIGHT,
                    LightTexture.FULL_BRIGHT,
                    LightTexture.FULL_BRIGHT,
                    LightTexture.FULL_BRIGHT},
                OverlayTexture.NO_OVERLAY,
                true);
        List<Vertex> rawVertices = consumer.finish();
        List<Vec2f> atlasUvs = rawVertices.stream().map(Vertex::uv).toList();
        AtlasSpriteResolver.Resolution resolution =
                atlasSpriteResolver.resolve(quad.getSprite(), atlasUvs);
        report(resolution, objectId, quad, diagnostics);
        SpriteTextureExtractor.Extraction texture = extraction(resolution.sprite());
        MaterialKey material = MaterialResolver.resolve(descriptor, texture.key());
        List<Vec2f> normalizedUvs = AtlasSpriteResolver.normalize(atlasUvs, resolution.region());
        List<Vertex> vertices = new ArrayList<>(rawVertices.size());
        for (int index = 0; index < rawVertices.size(); index++) {
            Vertex vertex = rawVertices.get(index);
            vertices.add(new Vertex(
                    vertex.position(),
                    vertex.normal().normalizedOrUp(),
                    normalizedUvs.get(index),
                    vertex.color()));
        }
        return new Captured(material, descriptor.primitiveMode(), vertices);
    }

    private SpriteTextureExtractor.Extraction extraction(TextureAtlasSprite sprite)
            throws IOException {
        SpriteTextureExtractor.Extraction texture = spriteCache.get(sprite);
        if (texture == null) {
            texture = spriteExtractor.extract(sprite);
            spriteCache.put(sprite, texture);
            textureRegistry.register(texture.key(), texture.image());
        }
        return texture;
    }

    private void report(
            AtlasSpriteResolver.Resolution resolution,
            String objectId,
            BakedQuad quad,
            List<Diagnostic> diagnostics) {
        if (resolution.kind() == AtlasSpriteIndex.Kind.REDIRECTED) {
            String key = resolution.declaredId() + "->" + resolution.resolvedId();
            if (reported.add(key)) {
                diagnostics.add(new Diagnostic(
                        Diagnostic.Severity.INFO,
                        "ATLAS_SPRITE_REDIRECTED",
                        objectId,
                        Optional.empty(),
                        quad.getClass().getName(),
                        "",
                        "Resolved atlas sprite " + resolution.declaredId()
                                + " to " + resolution.resolvedId()));
            }
        } else if (resolution.kind() == AtlasSpriteIndex.Kind.FALLBACK
                && reported.add("fallback:" + resolution.declaredId())) {
            diagnostics.add(new Diagnostic(
                    Diagnostic.Severity.WARNING,
                    "ATLAS_SPRITE_RESOLUTION_FAILED",
                    objectId,
                    Optional.empty(),
                    quad.getClass().getName(),
                    "",
                    "No atlas sprite covered the quad; kept declared sprite "
                            + resolution.declaredId()));
        }
    }
}
