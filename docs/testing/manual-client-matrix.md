# MineToMesh 1.2.0 Manual Client Acceptance Matrix

Target environment: Minecraft 1.21.1 and NeoForge 21.1.244, with `MineToMesh-1.2.0.jar` installed on both client and server. Except for the permission scenarios, use the same Export Wand throughout and check sound, item components, Overlay, GUI and the export directory.

## 0.5.1 hard identity migration

The following destructive behaviors must be confirmed manually on a copy of an old 0.5.0 world; automated tests only verify the mechanical structure.

| Scenario | Action | Expected result |
|---|---|---|
| New Mod identity | Start the client and open the mod list | Only `MineToMesh 0.5.1 (minetomesh)` is shown; the old Mod ID is not loaded |
| Old world registry entries | After a backup, open a copy of a 0.5.0 world containing old wands with 0.5.1 | The old identity is invalidated under NeoForge hard-migration rules; no Missing Mapping compatibility |
| Old wand data | Inspect the old item and obtain a new 0.5.1 wand | Old wand data is not migrated; the new wand can independently create selections and exports |
| Command switch | Enter `/minetomesh status` and the old command | The new command is registered; the old command does not exist and has no alias |
| Output directory switch | Keep an existing `.minecraft/mcgltf-exports/` and complete one new export | The old directory is left untouched; new results are written only to `.minecraft/minetomesh-exports/` |
| Client/server consistency | Connect with 0.5.1 installed on both client and server | Handshake and wand network payloads work, with no legacy-namespace compatibility channel |

## Wand input and selection

| Scenario | Action | Expected result |
|---|---|---|
| Crack-free left click | Plain left click on stone | The block shows no cracks and is not broken, POS1 updates, and only the point-set sound plays |
| Container-safe right click | Plain right click on a chest | The chest does not open, POS2 updates, and only the point-set sound plays |
| Left click air, two blocks | Plain left click with no block in front of the crosshair | POS1 is the air block two blocks ahead of the eyes along the line of sight |
| Right click air, two blocks | Plain right click with no block in front of the crosshair | POS2 is the air block two blocks ahead of the eyes along the line of sight |
| Shift + right click block | Sneak and right click any block | The GUI opens and the block itself is not interacted with |
| Shift + right click air | Sneak and right click air | The same GUI opens |
| Shift + left click air clears | With a complete selection, sneak and left click air | Both endpoints are cleared; the name and Overlay preference are kept |
| Shift + left click block | Sneak and left click a block | POS1 updates and no clear is triggered |
| Cross-dimension rejection | Set POS1 in the Overworld, then enter the Nether and try to set POS2 | The action is rejected and the Overworld POS1 is kept unchanged |
| Height limits | Select the lowest Y and the highest valid Y | `minBuildHeight <= y < maxBuildHeight` is accepted; out-of-range values are rejected |
| Two-wand isolation | Set different selections and names on two wands | `wandId`, endpoints, names and toggles never interfere with each other |

## GUI, Overlay and lifecycle

| Scenario | Action | Expected result |
|---|---|---|
| Coordinate editing | Enter complete X/Y/Z, then press Enter or lose focus | The complete endpoint is sent only when all three fields parse; invalid fields are marked red |
| Stepping | Buttons, wheel, Shift + wheel | `±1`, `±1` and `±10` respectively |
| Name persistence | Edit the export name, press Enter or lose focus, then close and reopen | The same wand keeps its name |
| Overlay depth | The selection passes through a wall | The orange volume and blue edge lines are occluded normally by solid blocks |
| Overlay hide and restore | Hold a wand with Overlay on, switch to another item, then switch back | It hides immediately when switched away and returns when the same wand is held again |
| Preview and lock independence | Toggle `Preview`, `Lock` and `Players` separately | The three 60×16 toggles never change each other's state; `Players` does not affect any preview |
| Still shown without the wand | Lock a complete selection, switch hotbar slots, and put the wand into a chest | The hand preview disappears and the lock box keeps showing |
| Lock survives restart | Lock, quit the process, restart and enter the same world or server | The lock box returns without holding the wand; the config is at `config/minetomesh/locked-selections.json` |
| Cross-world or server isolation | Lock in world A, then enter world B or another server | World B does not show world A's box; the record is still there after returning to world A |
| Dimension hide and restore | Lock in the Overworld, enter the Nether, then return to the Overworld | The box is hidden in the Nether because the dimension does not match, and is restored after returning with the record not deleted |
| Replace lock | With an existing lock, enter another complete selection in the GUI and click `Lock` | The current profile keeps only the new selection and the old box disappears |
| Remove lock | Click `Lock` again while the GUI coordinates match the current lock | Only the current world or server profile's record is deleted; the hand preview state is unchanged |
| Identical snapshot dedup | The held wand's selection is identical to the locked selection | Only one box is drawn per frame; if they differ, both are drawn in hand-then-lock order |
| Main hand priority | Hold complete wands in both the main hand and off hand | The main hand wand's selection is shown; the off hand is the fallback when the main hand has nothing to show |
| Moving the wand invalidates the menu | While the GUI is open, move, replace or drop the item in the bound slot | The GUI closes; a running export is cancelled |
| Close cancels | Press Esc during a large-selection export | The transaction directory is cleaned up and no half-finished official output remains |
| Reconnect grant lifecycle | Export in world A, quit, enter world B and export again | The second grant arrives normally and does not stall at "Waiting for server authorization" |
| Dimension grant | Request an export while the selection's dimension differs from the player's current one | The server rejects it and no client capture starts |

## Permission matrix

| Scenario | Environment | Expected result |
|---|---|---|
| Ordinary player | Dedicated server, permission below 2 | Can set points and edit; the export request is rejected |
| Administrator | Dedicated server, permission level 2 or higher | The export grant is approved |
| Singleplayer | Local integrated server without command permission | Singleplayer bypasses the permission check and the export grant is approved |
| Server isolation | Start a dedicated server in a headless environment | Prints `MINETOMESH_SERVER_READY` with no client-class linkage errors |

## Files and the Blender loop

| Scenario | Setup | Expected result |
|---|---|---|
| Vanilla blocks | Stone, grass, leaves, glass, water, chest | glTF, BIN, USDA, textures and `report.json` are all present; no OBJ/MTL is generated |
| Entities and block entities | Cow, armor stand, chest, sign | Geometry and textures appear; text, shadows and fire are skipped |
| Modded rendering | Create 6.0.10, Flywheel 1.0.6, Touhou Little Maid 1.5.3 | Gearbox, fluid tank and maid have no error placeholders, and the backend state is restored |
| glTF import | Import the `.gltf` in Blender 5.2 | Hierarchy, scale, axes, materials and UVs are correct, and faces are triangles |
| USDA / OpenUSD import | Import the `.usda` in Blender 5.2 through Universal Scene Description | Source quads are kept as quads, and every mesh has `subdivisionScheme = "none"` |
| Asymmetric coordinate orientation | Place a gold block on the east side and a diamond block on the west side of the selection, then import both glTF and USDA | Neither format is mirrored; Minecraft +X maps to Blender +X, and materials, normals and front-face direction are correct |
| USDA materials | Check grass, leaves, glass, lamps and pixel textures | PreviewSurface binds correctly; tint, cutout, blend and emission are recognizable; if the importer ignores `minetomesh:samplerMode`, set the Image Texture to `Closest` manually |
| Powered Rail exact coplanar | Export a powered rail whose top and bottom surfaces overlap exactly and inspect it up close | All quads are kept; later layers are offset by `1/1024` block along their own normals, with no black faces or flicker |
| Grass side Overlay layering | Export grass block sides and hide the Overlay object | Both the base layer and the tint Overlay exist, there is no Z-fighting when shown, and the base layer stays complete when hidden |
| Crops and ordinary static blocks | Export wheat, a cross-shaped plant and stone together | Non-overlapping faces keep their positions, and cross-shaped and ordinary block topology shows no extra drift |
| Hierarchy and global batching | Check Chunks, BlockEntities, Entities, Placeholders, Overlays | The hierarchy is complete; Create block entities are batched globally by complete material key |
| No USDA temporary fragments | Complete one export and cancel one large-selection export | Neither the success directory nor the temporary transaction directory leaves any `.usdapart` |
| Khronos validation | Run the Validator on the glTF | `numErrors: 0` |
| Unloaded chunks | Select across chunks beyond the render distance | No new chunks are force-loaded, and `report.json` records the missing chunks |
| Same-name export | Export the same name twice in a row | Produces `<name>/` and `<name>-2/` |

## 1.2.0 project-owner manual visual/interaction acceptance

The following items must be judged by the project owner in the real game and in Blender; automated tests only check structure and do not replace the visual verdict.

| Scenario | Action | Expected result |
|---|---|---|
| Create connected textures | Export joined andesite casings, brass casings, fluid tanks and vaults | Edge, corner and center textures match between the game and Blender, and do not fall back to repeating a single block |
| Create dynamic model handedness | Place ten connected belts east-west with an asymmetric marker on one side, add a cogwheel, shaft and mechanical arm, and import both glTF and USDA | Dynamic models are not mirrored along Blender Y; Minecraft +Z still maps to Blender -Y; belt front faces and vertex normals point outward |
| Create global material batching | Export ten belts with the same material, then add one dynamic part with a different material key | In both glTF and USDA, all block-entity geometry with the same complete `MaterialKey` forms a single mesh object; different material keys form separate objects |
| Batching boundaries | Add a cow or armor stand and ordinary static blocks to the same selection | Block entities are batched globally by material; ordinary entities stay separate; static blocks are still output by Chunk/Section structure |
| Grass side Overlay object | Export a grass area spanning several Sections | The Outliner shows a single `selection/grass_side_overlay`; only the base side faces remain when it is hidden |
| Latin E input | Type `create_export` in the name box | The characters enter the name normally, the GUI does not close, and the inventory does not open |
| CJK input method | Type Chinese text in the name box and commit | The Chinese name is saved and used for the output directory, and no hotkeys leak |
| Restore after close | Press E, WASD and 1 to 9 after closing the GUI with Esc | Game hotkeys are restored immediately with no sticky state |

## Sable region export (`/minetomesh sable <name>`)

This path writes the same glTF/USDA output as the Export Wand. It needs Sable Photomancy (`sable_schematic_api`) and LDLib2 on the client; Flywheel is optional.

| Scenario | Action | Expected result |
|---|---|---|
| Sable without the mods | Run `/minetomesh sable test` with Photomancy absent | The command refuses with a reason (`no-selection`) and writes nothing; the game keeps running |
| Selection refusals | Run with no selection, one corner, a box above 64 × 64 × 64, and an unsafe name | Refused with `no-selection`, `one-corner`, `too-large` and `unsafe-name` respectively; no partial output directory is left |
| Ship in a sub-level | Select a rotated ship and export | `<name>.gltf` has one node per coordinate space (`sable/world`, `sable/sub01_<name>`, ...), positioned correctly in Blender despite the rotation, with shading normals that follow the rotation; `<name>.usda` agrees |
| Flywheel instances | Export a cannon, encased cogwheel and bearing | The parts appear, and Flywheel is restored to its previous backend afterwards |
| Output files | Inspect the export directory | `.gltf`, `.bin`, `.usda`, `report.json`, `materials/` and one `textures/*.png` per sprite are present and the glTF validates; entities and fluids are not exported by design |
| Tint | Export grass and leaves in the world and on a ship | World blocks carry their biome colour as vertex colour; on a ship a quad whose tint cannot be resolved stays white without failing the block |
| Parity with the wand | Export the same small world selection with the wand and with `/minetomesh sable` | Same materials and texture names; the Sable export has more triangles because it does not cull faces between blocks |
| Failure isolation | Export while a ship holds a block entity whose renderer throws | The export completes, the part is missing, and `report.json` carries `BLOCK_ENTITY_CAPTURE_FAILED` for it |

## 1.2.0 release candidate acceptance record

- Automated: run `clean test build`, `ServerClassIsolationTest` and `runServerSmoke`.
- JAR: the file name must be `MineToMesh-1.2.0.jar`; it must contain `assets/minetomesh/`, `data/minetomesh/`, the Export Wand model, textures, recipe and the 77 GUI slices, and must not contain old package paths, old resource namespaces, `export_workstation`, the test mod or design documents.
- Real client: follow this matrix to complete the world A → world B reconnect loop, then install into a known PCL instance.
- Blender and real-mod visual acceptance are performed manually; automated tests do not replace the final visual judgment.
