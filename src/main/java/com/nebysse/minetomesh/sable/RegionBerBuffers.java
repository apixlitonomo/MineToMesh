package com.nebysse.minetomesh.sable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nebysse.minetomesh.capture.CapturingVertexConsumer;
import com.nebysse.minetomesh.capture.RenderTypeDescriptor;
import com.nebysse.minetomesh.capture.RenderTypeInspector;
import com.nebysse.minetomesh.scene.Diagnostic;
import com.nebysse.minetomesh.scene.MaterialKey;
import com.nebysse.minetomesh.scene.PrimitiveAccumulator;
import com.nebysse.minetomesh.scene.Vertex;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A {@link MultiBufferSource} for one block entity of a Sable export.
 *
 * <p>Every render type the renderer asks for gets one capturing consumer, and each consumer is
 * wrapped so that the space's placement ({@code frame . T(block - base)}) is applied to every
 * position and its normal matrix to every normal. A sub-level that is rotated in the world
 * therefore comes out with correctly rotated geometry <em>and</em> correctly rotated shading
 * normals - the OBJ writer never needed normals, glTF and USDA do.
 *
 * <p>When the renderer is finished, {@link #finishInto} resolves each render type to a material
 * the same way the ordinary block-entity capture does and appends the vertices to the space's
 * accumulator, so block-entity geometry ends up in the same node as the blocks around it.
 */
final class RegionBerBuffers implements MultiBufferSource {
    private final Matrix4f placement;
    private final Matrix3f normalMatrix;
    private final Map<RenderType, Entry> entriesByIdentity = new IdentityHashMap<>();
    private final List<Entry> entriesInFirstUseOrder = new ArrayList<>();
    private int vertices;

    RegionBerBuffers(Matrix4f placement) {
        this.placement = new Matrix4f(Objects.requireNonNull(placement, "placement"));
        this.normalMatrix = this.placement.normal(new Matrix3f());
    }

    /** How many vertices the renderer wrote, across every render type. */
    int vertices() {
        return vertices;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        Entry entry = entriesByIdentity.get(renderType);
        if (entry == null) {
            entry = new Entry(
                    RenderTypeInspector.inspect(renderType), new CapturingVertexConsumer());
            entriesByIdentity.put(renderType, entry);
            entriesInFirstUseOrder.add(entry);
        }
        return new PosedCapture(entry.consumer, placement, normalMatrix, this);
    }

    /**
     * Appends what the renderer wrote to the accumulator.
     *
     * @return how many render types contributed geometry
     */
    int finishInto(
            PrimitiveAccumulator accumulator,
            Function<RenderTypeDescriptor, MaterialKey> materialResolver,
            List<Diagnostic> diagnostics) {
        int contributed = 0;
        for (Entry entry : entriesInFirstUseOrder) {
            List<Vertex> captured = entry.consumer.finish();
            RenderTypeDescriptor descriptor = entry.inspection.descriptor();
            diagnostics.addAll(entry.inspection.diagnostics());
            if (descriptor.discard() || captured.isEmpty()) {
                continue;
            }
            accumulator.append(
                    materialResolver.apply(descriptor), descriptor.primitiveMode(), captured);
            contributed++;
        }
        entriesByIdentity.clear();
        entriesInFirstUseOrder.clear();
        return contributed;
    }

    private record Entry(RenderTypeInspector.Inspection inspection, CapturingVertexConsumer consumer) {
    }

    /**
     * Applies the current pose and then the space's placement to every vertex.
     *
     * <p>{@link CapturingVertexConsumer} deliberately ignores the pose it is handed, because on the
     * block path the pose is already baked into the numbers. A block-entity renderer is the other
     * case: it does not apply the stack itself and expects the consumer to, so this wrapper is what
     * makes a barrel's pitch or a bearing's angle visible in the export at all.
     */
    private static final class PosedCapture implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Matrix4f placement;
        private final Matrix3f normalMatrix;
        private final RegionBerBuffers counter;

        PosedCapture(
                VertexConsumer delegate,
                Matrix4f placement,
                Matrix3f normalMatrix,
                RegionBerBuffers counter) {
            this.delegate = delegate;
            this.placement = placement;
            this.normalMatrix = normalMatrix;
            this.counter = counter;
        }

        private void place(Matrix4f pose, float x, float y, float z) {
            Vector3f point = new Vector3f(x, y, z);
            if (pose != null) {
                pose.transformPosition(point);
            }
            placement.transformPosition(point);
            counter.vertices++;
            delegate.addVertex(point.x, point.y, point.z);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            place(null, x, y, z);
            return this;
        }

        @Override
        public VertexConsumer addVertex(Matrix4f pose, float x, float y, float z) {
            place(pose, x, y, z);
            return this;
        }

        @Override
        public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
            place(pose == null ? null : pose.pose(), x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            // By here the renderer's own pose has already been applied to the normal; what is left
            // is the space's rotation.
            Vector3f normal = new Vector3f(x, y, z);
            normalMatrix.transform(normal);
            if (normal.lengthSquared() > 0.0F) {
                normal.normalize();
            }
            delegate.setNormal(normal.x, normal.y, normal.z);
            return this;
        }
    }
}
