# MineToMesh

MineToMesh is a world-export mod for Minecraft 1.21.1 on NeoForge. It writes the selection currently loaded on the client as Blender-editable glTF 2.0 and text-based OpenUSD (USDA) scenes. Version 1.2.0 adds exact coplanar quad layering, which fixes Powered Rail and grass-block overlay layers turning black or flickering in Blender; USDA keeps the source quads and references external PNG textures through PreviewSurface materials. The Export Wand continues to store and edit selections: the server authoritatively validates the item identity, coordinates and permissions, while the client handles render capture, texture reads and file writing.

## Features

- Must be installed on both client and server; the target version is NeoForge 21.1.244.
- NeoForge shows an official mod-list cover image, and the author is displayed as `岚苍穹 nebysse` (Lan Cangqiong nebysse).
- Each Export Wand independently stores its `wandId`, dimension, POS1, POS2, Overlay toggle and export name; moving the item never loses data, and two wands never interfere with each other.
- Supports vanilla and modded `BakedModel`s, fluids, block-entity renderers and ordinary entity renderers.
- Dynamic block entities are batched globally by complete `MaterialKey`; consecutive geometry with the same material (for example conveyor belts) becomes a single mesh object in Blender.
- The real sprite is resolved from each quad's actual atlas UVs, which is compatible with Create 6.x `*_connected` texture sheets without any hard dependency on Create, Catnip or Flywheel.
- Vanilla grass blocks keep both the base side geometry and the tint overlay geometry; all overlays in the selection are merged into a single `selection/grass_side_overlay` object.
- Every export produces both glTF 2.0 and USDA, and the two formats share the same external PNG textures.
- glTF is triangulated as the specification requires; USDA keeps the captured source quads and fixes `subdivisionScheme = "none"`.
- Normal block quads that overlap exactly are offset layer by layer along their own normals by `1/1024` block; the first layer stays in place and all stacked geometry is kept.
- USDA uses PreviewSurface to express textures, vertex tint, transparency, double-sided rendering and emission. Nearest-neighbor sampling is written to `minetomesh:samplerMode` and the material sidecar. If the Blender importer does not read it, set the Image Texture interpolation to `Closest` manually.
- The output hierarchy is fixed as `Chunks`, `BlockEntities`, `Entities`, `Placeholders`, `Overlays`.
- Coordinates use the minimum corner of the selection as the local origin, and one block equals one Blender meter. The export space keeps Minecraft's relative `(X,Y,Z)` orientation and performs no axis reflection.
- After Blender imports the glTF it rotates from Y-up to Z-up: Minecraft `+X` → Blender `+X`, Minecraft `+Y` → Blender `+Z`, Minecraft `+Z` → Blender `-Y`.
- Unloaded chunks are never force-loaded; diagnostics are written to `report.json`.

## Installation

1. Install Minecraft 1.21.1 and NeoForge 21.1.244.
2. Put `MineToMesh-1.2.0.jar` into the `mods/` folder of **both the client and the server**, and remove any other MineToMesh JARs.
3. Start the game. Exported files are written on the client of the player who started the export.

The 1.2.0 runtime Mod ID is `minetomesh` and the Java root package is `com.nebysse.minetomesh`. Client and server must run the same version.

## Export Wand

Obtain it from the MineToMesh creative tab. The recipe is:

```text
  A
 RC
S  
```

- `A`: Amethyst Shard
- `R`: Redstone
- `C`: Copper Ingot
- `S`: Stick

### Controls

| Input | Target | Behavior |
|---|---|---|
| Plain left click | Block | Sets POS1; block breaking and crack animation are blocked |
| Plain right click | Block | Sets POS2; container and block interactions are blocked |
| Plain left click | Air | Sets POS1 to the air block two blocks ahead of the eyes along the crosshair direction |
| Plain right click | Air | Sets POS2 to the air block two blocks ahead of the eyes along the crosshair direction |
| Shift + left click | Block | Sets POS1 |
| Shift + left click | Air | Clears this wand's POS1/POS2 while keeping its name and Overlay preference |
| Shift + right click | Block or air | Opens the export GUI |

Setting points, clearing and opening the menu only play sound feedback. Setting an endpoint in a different dimension is rejected and the existing selection is kept.

### GUI and Overlay

- The GUI edits the X/Y/Z of POS1/POS2 directly; Enter or losing focus commits the complete endpoint.
- The step buttons and the mouse wheel change a value by `±1`; `Shift + wheel` changes it by `±10`.
- The export name supports Unicode: both Latin and CJK text enter the focused input box through `charTyped`, are written back to the current wand on Enter or focus loss, and persist after closing and reopening.
- While the GUI is open it consumes E, WASD, the number hotbar keys and mod hotkeys. Esc closes it, Enter only commits the current field, Tab only moves focus, and game hotkeys are restored immediately after closing.
- The GUI has three independent toggles: `Preview`, `Lock` and `Players`. `Preview` (hand preview) controls the current wand's Overlay; `Lock` (lock selection) controls the persistent client-side box; `Players` (export players) only affects what is exported.
- The Overlay is an orange translucent volume with blue depth-occluded edge lines. Hand preview requires the matching wand to remain in the main hand or off hand; a locked selection does not require holding the wand.
- Clicking `Lock` reads the six coordinates directly from the GUI. The first click saves the selection; clicking after choosing a different region replaces the old lock; clicking again while the current coordinates equal the locked ones removes the lock.
- Lock records are written to `.minecraft/config/minetomesh/locked-selections.json`, are restored across restarts, and are isolated per save or server. A record only contains a SHA-256 profile key and never stores the raw server address; the lock state is not written to the wand component and no new server network payload is sent.
- A locked region is hidden when the dimension does not match and restored when you return to the original dimension. Switching world or server only switches the profile and never deletes other profiles' records. A corrupted config is quarantined as `locked-selections.json.corrupt-*` and the client keeps starting with empty records.
- Moving, replacing or removing the bound wand while the GUI is open invalidates the menu, and any GUI export in progress is cancelled with it.

### Permissions and export lifecycle

- Singleplayer lets the local player export.
- A dedicated server requires permission level 2 or higher. Ordinary players can still set points and edit the wand, but cannot start a client export.
- After Export is clicked, the server re-reads the UUID, selection, dimension and export name from the item currently bound to the menu and returns an immutable snapshot.
- The client only accepts grants that match the current `wandId` and dimension. Leaving the world only clears the controller state; the network callbacks stay installed for the lifetime of the process.
- Closing the GUI during an export cancels the job and cleans up the transaction directory; a result that completed normally is not deleted by closing the GUI.

## Sable compatibility (`/minetomesh sable`)

MineToMesh can export a region from a Sable world, including structures that Sable keeps inside sub-levels (for example ships and contraptions), as glTF 2.0 and USDA, the same formats and the same output layout as the Export Wand. It reuses the selection of the Sable blueprint tool instead of the Export Wand, and every integration is optional and reached by reflection, so MineToMesh still loads normally without Sable.

**Setup**

1. Install **Sable Photomancy** (mod id `sable_schematic_api`) and **LDLib2**.
2. Install MineToMesh on the client as usual (see [Installation](#installation)). Flywheel is optional.

**Exporting a ship or structure**

1. With the Photomancy blueprint tool in library mode, left-click two opposite corners around your ship. Keep the selection at or below 64 × 64 × 64 blocks (131,072 blocks).
2. Run:

   ```text
   /minetomesh sable myship
   ```

3. Find the result in `.minecraft/minetomesh-exports/myship/`:

   ```text
   myship.gltf     scene, one node per coordinate space (`sable/world`, `sable/sub01_myship`, ...)
   myship.bin      geometry buffers for the glTF
   myship.usda     the same scene as USD, with quads preserved
   report.json     counters and diagnostics
   textures/*.png  one image per block sprite (and any entity sheet a renderer named)
   materials/      per-material sidecar files
   ```

Notes:

- The selection is the two corners set by left-clicking in the tool's library mode; Shift + left click clears it. It is client-side state, so it is empty after a blueprint is saved.
- The command refuses with a reason in the log (`no-selection`, `one-corner`, `too-large`, `unsafe-name`) instead of exporting an empty or oversized box.
- The ordinary world and every Sable sub-level intersecting the selection each become their own node holding that space's blocks and block entities, positioned correctly even when the sub-level is rotated. Sub-level geometry keeps correctly rotated normals.
- Parts that Flywheel draws as instances (cannons, encased cogwheels, bearings and similar) are recovered by temporarily switching Flywheel to its CPU fallback during capture; if Flywheel is absent, nothing extra is needed.
- Only blocks and block entities are exported (no entities), fluids are not exported, and faces between touching blocks are not culled. Block tint (grass, leaves) is applied as vertex colour; a tint that cannot be resolved inside a sub-level's storage space leaves that quad white.
- See `src/main/java/com/nebysse/minetomesh/sable/README-SABLE-EXPORT.md` for the full technical description and the log lines that explain each export.

## 0.5.1 hard migration warning

Version 0.5.1 fully migrated the old Mod ID `mcgltf` to `minetomesh`. It provides no Missing Mapping, no legacy resource shim and no `/mcgltf` command alias, so it is **not compatible with old saves** that contain old registry entries and old wand data. Back up your world before upgrading; old wands are not converted into wands under the new identity.

The old `.minecraft/mcgltf-exports/` directory is not moved or deleted, and new exports are always written to `.minecraft/minetomesh-exports/`. If you need old results, read them directly from the old directory. The hard removal of the region export workstation in 0.5.0 still applies; the old workstation is neither restored nor converted.

## Command fallback

```text
/minetomesh pos1
/minetomesh pos2
/minetomesh export <name>
/minetomesh export <name> confirm
/minetomesh status
/minetomesh cancel
/minetomesh sable <name>
```

`/minetomesh` is a fallback entry point that needs no wand, and no legacy command alias is kept. When a selection exceeds the soft limit of 4,194,304 blocks, you must run the `confirm` command shown in chat. `/minetomesh sable <name>` is the Sable region export described above.

## Output

The output root is `.minecraft/minetomesh-exports/`:

```text
minetomesh-exports/<name>/
├─ <name>.gltf
├─ <name>.bin
├─ <name>.usda
├─ report.json
├─ textures/
└─ materials/
```

A directory with the same name automatically gets a suffix such as `name-2` or `name-3`. Capture uses rolling snapshots with a budget of about 6 ms per client tick, and the background write queue has a capacity of 2.

## Importing into Blender

In Blender 5.2 choose "File → Import → glTF 2.0" to open the `.gltf`; when you need to keep quads, choose "File → Import → Universal Scene Description" to open the `.usda`. Keep the relative layout of `.gltf`, `.bin`, `.usda` and `textures/` unchanged. The USDA stage is Y-Up with `metersPerUnit = 1`, and Blender performs the Y-up to Z-up axis rotation on import. How PreviewSurface renders Cutout, Blend, vertex tint and emission depends on the Blender USD importer, so complex materials should still be checked manually after import.

Version 1.2.0 no longer generates OBJ/MTL for the standard export and offers no compatibility switch; the Sable region export writes the same glTF/USDA output instead of OBJ. No `.usdapart` temporary fragments should remain after a successful or cancelled export.

Supported backends such as Flywheel 1.x temporarily enter a CPU fallback rendering scope during capture and are restored on the `finally` path. Objects generated purely by shaders with no CPU geometry cannot be recovered; they produce a translucent magenta bounding box and a diagnostic code is recorded. Shadows, fire, name text, Minecraft lighting and AO are not baked.

## Build and verification

```powershell
./gradlew.bat clean test build
./gradlew.bat runServerSmoke
Set-Location tools
npm install
npm run validate -- ..\run\minetomesh-exports\smoke\smoke.gltf
```

For real-mod acceptance testing, use Create 6.0.10, Flywheel 1.0.6 and Touhou Little Maid 1.5.3: import both the glTF and the USDA into Blender 5.2, compare origin, scale, materials, UVs, quad topology and the Powered Rail layering, and require `numErrors: 0` from the Khronos Validator for the glTF.

## License

MIT, see [LICENSE](LICENSE).
