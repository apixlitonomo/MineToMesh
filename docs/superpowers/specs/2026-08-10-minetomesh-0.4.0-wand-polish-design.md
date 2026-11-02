# MineToMesh 0.4.0 Wand Interaction and Visual Polish Design

Date: 2026-08-10  
Status: awaiting the user's written review  
Baseline branch: `feature/mcgltf-0.4.0-export-wand`

## 1. Goals

Add four closed-loop improvements on top of the completed 0.4.0 Export Wand architecture:

1. Strictly isolate keyboard events while the GUI is open, allowing only Esc to exit, while keeping text editing working.
2. Add an "Export Players" checkbox, persisted independently by each wand and off by default.
3. Change the selection Overlay into an orange world-border texture shell, and show single-block translucent endpoint boxes for POS1/POS2.
4. Replace the Export Wand item texture with the confirmed 32×32 transparent pixel texture.

This round keeps the version number `0.4.0`; after completion the candidate JAR is rebuilt and replaced.

## 2. Confirmed behavior

### 2.1 GUI keyboard isolation

- While the GUI is open `passEvents=false`.
- Only Esc can close the GUI.
- `E`, the number hotbar keys, movement keys and any other game or mod hotkeys must neither close the GUI nor reach the game.
- While a text box is focused, characters, digits, the minus sign and `Ctrl+A/C/V/X` act only on the text box.
- Enter only commits the currently focused coordinate endpoint or export name, and does not start the export directly.
- Tab only moves focus between GUI controls.
- `keyPressed`, `keyReleased` and `charTyped` not handled by a control are still consumed by the Screen.
- Mouse buttons, the wheel and GUI buttons keep their existing behavior.

The implementation must not "fix" the leak by clearing the global KeyMapping state, to avoid breaking normal input after the GUI closes.

### 2.2 Export Players option

- `ExportWandSelection` gains an `includePlayers` boolean field.
- The default is `false`; an old ItemStack lacking the field must decode safely to `false`.
- Each wand saves this value independently, it is kept after the item is moved, and two wands do not affect each other.
- The original Overlay button at the bottom of the GUI left column is split into two side-by-side 92×16 slice-style checkbox buttons:
  - Left: `Selection Display`
  - Right: `Export Players`
- The server modifies the bound ItemStack through a menu-scoped payload, and rejects the write when the slot or `wandId` does not match.
- When Export is clicked, the server reads this value from the bound wand and writes it into the immutable grant snapshot.
- Changing the toggle after the export has started does not affect the already approved job.
- When enabled, all visible players inside the selection are exported, including the local player who started the export.
- When disabled, all players continue to be excluded, preserving the old behavior.
- Removed players are not exported; a player must still intersect the selection AABB.
- Invisible players, or players whose renderer produced no exportable vertices, follow the existing entity capture degradation and diagnostics policy and no visible skin is artificially generated.
- The `/mcgltf` command fallback keeps using `includePlayers=false` and does not change existing command behavior.

## 3. Data and authorization chain

### 3.1 Data Component

The field order of `ExportWandSelection` is extended to:

```text
wandId
selectionDimension
pos1
pos2
overlayEnabled
includePlayers
exportName
```

The persistent Codec uses `optionalFieldOf("include_players", false)`; the StreamCodec reads and writes the field in sync on client and server.

A new immutable modification method is added:

```text
withIncludePlayers(boolean)
```

Clearing the endpoints keeps `overlayEnabled`, `includePlayers` and `exportName`.

### 3.2 Network

New:

```text
ToggleWandIncludePlayersPayload(boolean enabled)
```

Server handling requires:

```text
ServerPlayer
current containerMenu is ExportWandMenu
menu.resolveBoundStack(player) succeeds
```

`ExportWandGrantedPayload` gains `boolean includePlayers`. The Request still carries only the export name, and this option cannot be forged by the client request body.

### 3.3 Controller and pipeline

New value object:

```text
ExportOptions(boolean includePlayers)
```

Data flow:

```text
Grant
→ ExportWandController.JobStarter
→ DefaultExportPipeline.create
→ ProductionCaptureSource
→ EntityCapture.collect/captureAll
```

`EntityCapture.shouldInclude` gains an `includePlayers` parameter. Non-player entity logic is unchanged; players pass the filter only when the toggle is on.

The export root `extras` records `includePlayers` for traceability.

## 4. Overlay design

### 4.1 Data source

`HeldWandOverlaySource` no longer returns only a complete `Selection`, but a snapshot containing:

```text
Optional<BlockPos> pos1
Optional<BlockPos> pos2
Optional<Selection> selection
ResourceLocation dimension
```

The selection rule is still main hand first with off-hand fallback. The wand must have Overlay on and a matching dimension. Even with only one endpoint, that endpoint box is shown; the main selection shell is shown only when both endpoints are complete.

### 4.2 Main selection

- Uses the vanilla world-border texture `minecraft:textures/misc/forcefield.png`.
- Builds a translucent, depth-tested RenderType suited to `POSITION_COLOR_TEX`.
- All six faces are textured, with UVs repeating by world size rather than stretching the whole texture across a face.
- UVs scroll slowly over time, keeping the energy-wall semantics of the world border.
- Vertex colors are uniformly tinted with the MineToMesh orange `#ED741C`.
- The existing blue overall edge lines are removed.
- The shell still respects depth occlusion by solid blocks.

### 4.3 Endpoint boxes

- POS1: a one-block AABB with gray translucent faces and a gray-white outline.
- POS2: a one-block AABB with white translucent faces and a white outline.
- The AABB range runs from the endpoint block coordinate to the coordinate `+1`.
- Endpoint boxes are drawn after the main shell, with a tiny outward expansion to avoid coplanar flicker with the shell.
- When the two endpoints coincide they are drawn in POS1-then-POS2 order, so the white POS2 is the final visible layer.

## 5. 32×32 wand texture

The confirmed visual direction: an orange-copper thick measuring rod, an amethyst sensor and an ice-blue ranging module.

Processing discipline:

1. Use a pure green-screen `#00FF00` master image.
2. Finish the chroma key and green spill cleanup first.
3. Then crop the subject and leave a one-pixel safety margin.
4. Downscale to 32×32 with Pillow `Image.Resampling.NEAREST`; Bilinear, Bicubic, Lanczos or GPU linear sampling are forbidden.
5. The final PNG uses a transparent background and whole-pixel alpha, with no translucent anti-aliased edges.

Automatic check results of the candidate: 32×32, 760 transparent pixels, 264 opaque pixels, 0 translucent pixels, 0 leftover green pixels.

Production resource override:

```text
src/main/resources/assets/mcgltf/textures/item/export_wand.png
```

The item model path is unchanged.

## 6. GUI layout

The two buttons at the bottom of the left column share the original Overlay row:

```text
x = LEFT.x + 12,  width = 92   → Selection Display
x = LEFT.x + 108, width = 92   → Export Players
height = 16
```

The two buttons do not intersect the third coordinate box of POS2 or the step buttons. The existing GUI slices, OFF/ON indicators and the 8-physical-pixel nine-slice policy are kept.

## 7. Error handling

- No bound wand, slot change or UUID mismatch: ignore the toggle payload and do not modify other ItemStacks.
- Old component lacking the new field: decode as `includePlayers=false`.
- Grant UUID or dimension mismatch: the Controller rejects it.
- Missing world-border texture resource: log one warning and skip the main shell; endpoint boxes can still be drawn; it must not crash the client.
- Input returns to normal after the GUI closes, with no sticky key state retained.

## 8. Tests and acceptance

### Automated tests

- `ExportWandSelection` Codec and StreamCodec old/new defaults, copying and preservation on clear.
- `ToggleWandIncludePlayersPayload` round trip.
- Menu binding success, and rejection of modification for a wrong slot or wrong UUID.
- The Grant Codec carries an immutable `includePlayers`.
- The Controller passes `ExportOptions` to the JobStarter.
- `EntityCapture.shouldInclude` covers toggle off, toggle on, players, self, removed entities and boundary intersection.
- The Overlay Source covers a single endpoint, two endpoints, main/off-hand priority, dimension and toggle.
- The Renderer contract covers the forcefield texture, orange color, six textured faces, gray-white endpoints and the absence of blue overall edge lines.
- The GUI layout covers the two 92×16 buttons not overlapping.
- GUI keyboard tests cover Esc, E, letters, digits, Enter, Tab, clipboard combos and character events.
- Texture resource tests cover 32×32, alpha and no green-screen residue.

### Final verification

```text
clean test build
runServerSmoke
JAR contents and metadata audit
real-client keyboard input test
player toggle off/on comparison export
manual visual acceptance of single-endpoint and complete-selection Overlay
import of the glTF/OBJ containing players into Blender
```

## 9. Out of scope for this round

- Player type whitelist, choosing players by UUID.
- Forcing invisible players to become visible in the export.
- Adding a player toggle argument to the `/mcgltf` command.
- Overlay color customization.
- Bumping the version number to 0.4.1.
