# MineToMesh

MineToMesh is a world-export mod for Minecraft 1.21.1 NeoForge. It writes the client's currently loaded selection as a Blender-editable glTF 2.0 scene and a text OpenUSD (USDA) scene. Version 1.2.0 adds exact coplanar Quad layering, which fixes Powered Rail and grass block overlays turning black or flickering in Blender. USDA keeps the source Quads and references external PNGs through PreviewSurface materials. The export wand continues to store and edit selections. The server authoritatively validates item identity, coordinates, and permissions, while the client handles render capture, texture reading, and file writing.

## Features

- Both client and server must have the mod installed. The target version is NeoForge 21.1.244.
- NeoForge shows an official mod list cover, with the author displayed as `岚苍穹 nebysse`.
- Each export wand independently stores its `wandId`, dimension, POS1, POS2, Overlay toggle, and export name. Moving the item does not lose data, and two wands never cross-contaminate each other.
- Supports vanilla and modded `BakedModel`, fluids, block entity renderers, and ordinary entity renderers.
- Dynamic block entities are batched globally by complete `MaterialKey`. Contiguous geometry with the same material, such as conveyor belts, becomes a single mesh object in Blender.
- Looks up the real Sprite from each Quad's actual Atlas UV, which is compatible with Create 6.x `*_connected` texture tables, without a hard dependency on Create, Catnip, or Flywheel.
- Vanilla grass blocks keep both the base side face and the Tint Overlay geometry. The Overlays for the whole selection are merged into one `selection/grass_side_overlay` object.
- Each export produces both glTF 2.0 and USDA, which share external PNG textures.
- glTF is triangulated per the specification. USDA keeps the original captured Quads and fixes `subdivisionScheme = "none"`.
- Exactly overlapping ordinary block Quads are offset layer by layer along their own normals by `1/1024` block. The first layer's position is unchanged, and all stacked geometry is kept.
- USDA uses PreviewSurface to express texture, vertex Tint, transparency, double-sidedness, and emission semantics. Nearest-neighbor semantics are written to `minetomesh:samplerMode` and to the material sidecar. If the Blender importer does not read it, set the Image Texture interpolation to `Closest` manually.
- The output hierarchy is fixed as `Chunks`, `BlockEntities`, `Entities`, `Placeholders`, `Overlays`.
- Coordinates use the selection's minimum point as the local origin, with one block equal to one Blender meter. The export space preserves Minecraft's relative `(X,Y,Z)` orientation and performs no axis reflection.
- After Blender imports the glTF, it applies the Y-up to Z-up axis rotation: Minecraft `+X` → Blender `+X`, Minecraft `+Y` → Blender `+Z`, Minecraft `+Z` → Blender `-Y`.
- Unloaded chunks are never force-loaded. Diagnostics are written to `report.json`.

## Installation

1. Install Minecraft 1.21.1 and NeoForge 21.1.244.
2. Put `MineToMesh-1.2.0.jar` into the `mods/` directory of **both the client and the server**, and remove any other MineToMesh JARs.
3. Launch the game. The exported files are written on the client of the player who started the operation.

The runtime Mod ID for 1.2.0 is `minetomesh`, and the Java root package is `com.nebysse.minetomesh`. Client and server must use the same version.

## Export Wand

It can be obtained from the MineToMesh creative tab. The recipe is:

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
| Plain left-click | Block | Sets POS1, prevents block breaking and crack animation |
| Plain right-click | Block | Sets POS2, prevents container or block interaction |
| Plain left-click | Air | Sets POS1 to the air block two blocks ahead of the eye along the crosshair direction |
| Plain right-click | Air | Sets POS2 to the air block two blocks ahead of the eye along the crosshair direction |
| Shift+left-click | Block | Sets POS1 |
| Shift+left-click air | Air | Clears this wand's POS1/POS2, keeping the name and Overlay preference |
| Shift+right-click | Block or air | Opens the export GUI |

Setting points, clearing, and opening the menu only play sound feedback. Setting an endpoint across dimensions is rejected, and the original selection is kept.

### GUI and Overlay

- The GUI lets you edit the X/Y/Z of POS1/POS2 directly. Enter or losing focus commits the complete endpoint.
- Step buttons and the scroll wheel change values by `±1`, and `Shift+scroll` changes them by `±10`.
- The export name supports Unicode. Chinese and English input enters the focused text box via `charTyped`, is written back to the current wand on Enter or losing focus, and persists after closing and reopening.
- While the GUI is open, it consumes E, WASD, number hotbar keys, and mod keybinds. Esc closes it, Enter only commits the current field, Tab only switches focus, and game keybinds are restored immediately after closing.
- The GUI provides three independent toggles: `Hand Preview`, `Lock Selection`, and `Export Players`. Hand Preview controls the current wand's Overlay. Lock Selection controls the client's persistent frame. Export Players only affects the exported content.
- The Overlay consists of an orange translucent volume and blue depth-occluded edge lines. Hand Preview requires the corresponding wand to still be in the main hand or off hand. Lock Selection does not require continuing to hold the wand.
- Clicking Lock Selection reads the six coordinates in the GUI directly. The first click saves them. Clicking after selecting a different region replaces the old lock. Clicking again when the current coordinates match the lock removes it.
- Lock records are written to `.minecraft/config/minetomesh/locked-selections.json` and can be restored across restarts, isolated per save or server. Records contain only a SHA-256 Profile key and do not store the raw server address. Lock state is not written to the wand component and does not send any new server network payload.
- A locked region is hidden when the dimension does not match and is restored when you return to the original dimension. Switching worlds or servers only switches the Profile and does not delete other Profiles' records. A corrupted config is quarantined as `locked-selections.json.corrupt-*`, and the client continues starting with empty records.
- Moving, replacing, or removing the bound wand while the GUI is open invalidates the menu, and any GUI export in progress is canceled.

### Permissions and Export Lifecycle

- Single-player mode allows the local player to export.
- Dedicated servers require permission level 2 or higher. Regular players can still set points and edit the wand, but cannot start a client export.
- When Export is clicked, the server re-reads the UUID, selection, dimension, and export name from the item currently bound to the menu, and returns an immutable snapshot.
- The client only accepts authorizations that match the current `wandId` and dimension. Leaving the world only clears Controller state, and network callbacks remain installed for the lifetime of the process.
- Closing the GUI during an export cancels the task and cleans up the transaction directory. A normally completed result is not deleted by closing the GUI.

## 0.5.1 Hard Migration Warning

Version 0.5.1 fully migrated the old Mod ID `mcgltf` to `minetomesh`. It provides no Missing Mapping, no legacy resource shim, and no `/mcgltf` command alias, so it is **not compatible with old saves** containing old registry entries and old wand data. Back up your world before upgrading. Old wands are not converted into wands under the new identity.

The old `.minecraft/mcgltf-exports/` directory is neither moved nor deleted, and new exports are all written to `.minecraft/minetomesh-exports/`. If you need old results, you can read them directly from the old directory. The hard removal of the region export workbench in 0.5.0 remains in effect, and old workbenches are not restored or converted.

## Command Fallback

```text
/minetomesh pos1
/minetomesh pos2
/minetomesh export <name>
/minetomesh export <name> confirm
/minetomesh status
/minetomesh cancel
```

`/minetomesh` is a fallback entry point that needs no wand, and no legacy command aliases are kept. When a selection exceeds the soft limit of 4,194,304 blocks, you need to run the `confirm` command shown in chat.

## Output

The output root directory is `.minecraft/minetomesh-exports/`:

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

In Blender 5.2, choose "File → Import → glTF 2.0" and open the `.gltf`. If you need to keep Quads, choose "File → Import → Universal Scene Description" and open the `.usda`. Keep the relative directory layout of `.gltf`, `.bin`, `.usda`, and `textures/` unchanged. The USDA stage is Y-Up with `metersPerUnit = 1`, and Blender performs the Y-up to Z-up axis rotation on import. How PreviewSurface renders Cutout, Blend, vertex Tint, and emission depends on the capabilities of Blender's USD importer, so complex materials should still be checked manually after import.

Version 1.2.0 no longer generates OBJ/MTL and provides no compatibility switch. No `.usdapart` temporary fragments should remain after a successful or canceled export.

Supported backends such as Flywheel 1.x temporarily enter a CPU fallback rendering scope during capture and are restored on the `finally` path. Objects generated purely by shaders with no CPU geometry cannot be recovered in reverse. They produce a translucent magenta bounding box and record a diagnostic code. Shadows, fire, name text, Minecraft lighting, and AO are not baked.

## Build and Verification

```powershell
./gradlew.bat clean test build
./gradlew.bat runServerSmoke
Set-Location tools
npm install
npm run validate -- ..\run\minetomesh-exports\smoke\smoke.gltf
```

For real-mod acceptance testing, we recommend using Create 6.0.10, Flywheel 1.0.6, and Touhou Little Maid 1.5.3. Import both glTF and USDA into Blender 5.2 and compare origin, scale, materials, UVs, Quad topology, and Powered Rail layering. The Khronos Validator must report glTF `numErrors: 0`.

## License

MIT. See [LICENSE](LICENSE) for details.
