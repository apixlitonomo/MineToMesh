package com.nebysse.minetomesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentationPolicyTest {
    @Test
    void readmeDocumentsTheWandReleaseAndMigration() throws Exception {
        String readme = read("README.md");
        for (String fragment : List.of(
                "MineToMesh-1.2.0.jar",
                "both the client and the server",
                "Export Wand",
                "Shift + right click",
                "Shift + left click",
                "Amethyst Shard",
                "Copper Ingot",
                "permission level 2",
                "not compatible with old saves",
                "actual atlas UVs",
                "selection/grass_side_overlay",
                "Overlays",
                "Distant Horizons",
                "LodChunks",
                "Unicode",
                "/minetomesh",
                "com.nebysse.minetomesh",
                ".minecraft/minetomesh-exports/",
                "provides no Missing Mapping",
                "The old `.minecraft/" + "mc" + "gltf-exports/` directory is not moved or deleted",
                "performs no axis reflection",
                "Minecraft `+X` → Blender `+X`",
                "Minecraft `+Y` → Blender `+Z`",
                "Minecraft `+Z` → Blender `-Y`",
                "USDA",
                "OpenUSD",
                "quads",
                "subdivisionScheme",
                "Closest",
                "File → Import → Universal Scene Description",
                "Version 1.2.0 no longer generates OBJ/MTL",
                "`Preview`",
                "`Lock`",
                "restored across restarts",
                "isolated per save or server",
                "hidden when the dimension does not match",
                "restored when you return to the original dimension",
                "config/minetomesh/locked-selections.json",
                "mod-list cover")) {
            assertTrue(readme.contains(fragment),
                    "README must mention: " + fragment);
        }
        assertFalse(readme.contains("Craft the region export workstation"));
        assertFalse(readme.contains("Place the region export workstation"));
        assertFalse(readme.contains("/" + "mc" + "gltf pos1"));
        assertFalse(readme.contains("(X,Y,Z) → (X,Y,-Z)"));
        assertFalse(readme.contains("Every export produces both glTF 2.0 and OBJ"));
    }

    @Test
    void manualMatrixCoversTheExactWandClosure() throws Exception {
        String matrix = read("docs/testing/manual-client-matrix.md");
        for (String fragment : List.of(
                "Crack-free left click",
                "Container-safe right click",
                "Shift + right click block",
                "Shift + right click air",
                "Shift + left click air clears",
                "Cross-dimension rejection",
                "Two-wand isolation",
                "Overlay hide and restore",
                "Moving the wand invalidates the menu",
                "Reconnect grant lifecycle",
                "Ordinary player",
                "Administrator",
                "Singleplayer",
                "glTF",
                "USDA",
                "OpenUSD",
                "Blender",
                "Create connected textures",
                "Grass side Overlay object",
                "Latin E input",
                "CJK input method",
                "Restore after close",
                "New Mod identity",
                "Old world registry entries",
                "Old wand data",
                "Command switch",
                "Output directory switch",
                "Client/server consistency",
                "Asymmetric coordinate orientation",
                "Powered Rail exact coplanar",
                "subdivisionScheme",
                "Closest",
                "No USDA temporary fragments",
                "Preview and lock independence",
                "Lock survives restart",
                "Cross-world or server isolation",
                "Dimension hide and restore",
                "Replace lock",
                "Remove lock")) {
            assertTrue(matrix.contains(fragment),
                    "manual matrix must cover: " + fragment);
        }
    }

    private static String read(String relative) throws Exception {
        return Files.readString(projectRoot().resolve(relative),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("user.dir")).getParent().getParent();
    }
}
