package com.nebysse.minetomesh.sable;

import com.nebysse.minetomesh.job.DefaultExportPipeline;
import com.nebysse.minetomesh.output.ExportName;
import com.nebysse.minetomesh.output.OutputTransaction;
import com.nebysse.minetomesh.output.StreamingSceneSession;
import com.nebysse.minetomesh.report.ExportReport;
import com.nebysse.minetomesh.report.ReportWriter;
import com.nebysse.minetomesh.scene.ChunkBatch;
import com.nebysse.minetomesh.scene.MaterialKey;
import com.nebysse.minetomesh.texture.TextureRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes a captured Sable region through the same output chain the Export Wand uses.
 *
 * <p>The sequence is the one {@code DefaultExportPipeline} runs on its writer thread: the nodes go
 * through {@link StreamingSceneSession} (glTF + BIN + USDA), the textures are written next to
 * them, the material sidecars follow, the glTF is validated, and only then is the transaction
 * published. A failed validation throws before publishing, so a broken export never replaces a
 * good directory. It runs on the calling thread because the capture is client-thread work anyway.
 */
final class RegionSceneWriter {
    private RegionSceneWriter() {
    }

    /**
     * @param exportRoot {@code minetomesh-exports}
     * @param report     built by the caller; written as {@code report.json} after the scene
     * @return the published export directory
     */
    static Path write(
            Path exportRoot,
            ExportName name,
            Map<String, Object> rootExtras,
            ChunkBatch batch,
            TextureRegistry textures,
            ExportReport report) throws IOException {
        Objects.requireNonNull(exportRoot, "exportRoot");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rootExtras, "rootExtras");
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(textures, "textures");
        Objects.requireNonNull(report, "report");
        try (OutputTransaction transaction = OutputTransaction.begin(exportRoot, name)) {
            Path directory = transaction.temporaryDirectory();
            Set<MaterialKey> materials = new LinkedHashSet<>();
            batch.nodes().forEach(node -> node.primitives()
                    .forEach(primitive -> materials.add(primitive.material())));

            StreamingSceneSession.OutputStatistics output;
            try (StreamingSceneSession session =
                         new StreamingSceneSession(directory, name.value(), rootExtras)) {
                session.append(batch);
                textures.writeAll(directory);
                output = session.finish();
            }
            DefaultExportPipeline.writeMaterialSidecars(directory, materials, textures);
            List<String> errors = DefaultExportPipeline.validate(output.gltf().gltfPath());
            if (!errors.isEmpty()) {
                throw new IOException("Internal glTF validation failed with " + errors.size()
                        + " error(s): " + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
            }
            ReportWriter.write(directory, report);
            return transaction.publish();
        }
    }
}
