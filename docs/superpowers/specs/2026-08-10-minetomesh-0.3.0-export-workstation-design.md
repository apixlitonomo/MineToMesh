# MineToMesh 0.3.0 Region Export Workstation Design

Date: 2026-08-10  
Status: design confirmed section by section, awaiting written specification review  
Target version: 0.3.0  
Minecraft: 1.21.1  
NeoForge: minimum 21.1.244

## 1. Goals

MineToMesh 0.3.0 adds a placeable, craftable "Region Export Workstation" with a persistent selection. After right-clicking the block the player opens a full GUI, edits the selection through two three-dimensional integer coordinates, observes the selection volume, and starts the existing glTF and OBJ export pipeline from the client.

This version also changes the mod from a pure client-side mod to a dual-side mod that must be installed on both client and server. The server is responsible for the block, the selection, the menu, permission validation and multiplayer synchronization; render capture, texture reading and file writing are still performed only on the client of the player who started the action.

## 2. Confirmed product decisions

- Selection data belongs to the operated block, not to the player.
- Each workstation stores one start point and one end point, shared by all players.
- Coordinates support manual input, up/down buttons, the mouse wheel and feet-position picking.
- The up/down buttons always adjust by `±1`.
- Hovering an input box, the wheel adjusts by `±1`, and `Shift + wheel` adjusts by `±10`.
- The selection display can be toggled in the GUI and keeps showing after the GUI is closed.
- The selection uses orange translucent volume surfaces and blue depth auxiliary edge lines.
- The selection follows normal depth occlusion and is not shown through walls.
- Closing the GUI immediately cancels an export in progress and cleans up the transaction directory.
- The in-game report shows only a summary; the full diagnostics are still written to `report.json`.
- The export name is generated automatically by default, and the player can change it in the GUI, but it is not written into the block entity.
- The workstation is obtainable in creative mode and also has a survival recipe.
- No upper limit is placed on the selection volume.
- The existing `/mcgltf` client command is kept.
- The visuals use a Blender-tribute palette: orange, blue, dark gray and mid gray.
- The GUI uses a two-column dashboard with light bevels; the information hierarchy references Create, but neither depends on nor copies Create's code and assets.
- The block is an industrial device with a clear horizontal facing, and its front icon is a blue orthographic wireframe cube with a small orange arrow shooting from the inside toward the upper right.
- The final block textures use the native `16×16` resolution.

## 3. Out of scope

0.3.0 does not implement the following:

- Server-side render capture or server-side file export.
- A world point-picking tool, a wand or WorldEdit-style left/right-click point picking.
- A full in-game diagnostics browser, log filtering, log copying or opening the system directory.
- A persistent HUD progress bar.
- Export pause, resume or reconnection resume.
- A workstation inventory, redstone control, dynamic emission or dynamic block models.
- A soft warning, second confirmation or hard limit on selection volume.
- A runtime dependency on the Create mod.
- A redesign of the 0.2.0 capture backend, the dual-format topology and the texture compatibility policy.

## 4. Overall architecture

NeoForge `AbstractContainerMenu`, `BlockEntity` and explicit `CustomPacketPayload`s are combined.

```text
ExportWorkstationBlock
  └─ ExportWorkstationBlockEntity
       ├─ first: BlockPos
       └─ second: BlockPos

Server ExportWorkstationMenu
  ├─ validates block identity and player distance
  ├─ synchronizes the six coordinate integers
  ├─ receives legal edits
  └─ produces an immutable export snapshot

Client ExportWorkstationScreen
  ├─ coordinate editing and local input validation
  ├─ export name editing
  ├─ ExportTelemetry display
  └─ summary report display

Client WorkstationExportController
  ├─ receives the server authorization snapshot
  ├─ creates DefaultExportPipeline
  ├─ bridges ExportJobManager
  └─ cancels the job when the GUI closes

Client SelectionOverlayRenderer
  └─ draws the workstation selection currently enabled for the player
```

### 4.1 Package boundaries

The following packages are added:

```text
com.onecuber.mcgltf.content
com.onecuber.mcgltf.workstation
com.onecuber.mcgltf.network
com.onecuber.mcgltf.client.workstation
```

`content` is only responsible for registry entry points; `workstation` holds the dual-side-safe block, block entity, menu and value objects; `network` holds the payloads and server handling; `client.workstation` holds the Screen, widgets, selection rendering and the client export controller.

Classes reachable from the server must not statically reference `Minecraft`, `GuiGraphics`, renderers, client textures or the existing client capture classes.

## 5. Workstation block and persistence

### 5.1 Block

`ExportWorkstationBlock` uses a horizontal facing property. When placed, the front faces away from the placing player's line of sight, so that the face with the icon points toward the player. Right-clicking with an empty hand or any ordinary item opens the menu, and the item in hand is not consumed.

Properties:

- Metal sounds.
- Hardness `3.0`.
- Blast resistance `6.0`.
- Requires the correct pickaxe-type tool.
- No inventory.
- No redstone input or output.
- No dynamic `LIT` state.

When the player is more than eight blocks from the workstation, the block is removed, or the original position is no longer the same type of workstation, `stillValid` returns false and closes the menu.

### 5.2 Block entity

`ExportWorkstationBlockEntity` stores two `BlockPos`. On first placement both points are initialized to the block's own coordinates. The dimension is not written into the NBT again and always uses the dimension of the world the block entity is in.

The NBT fields are fixed as:

```text
First: int[3]
Second: int[3]
```

When a field is corrupted or missing on read, it falls back to the workstation's own coordinates. Saving and reading both copy values and never expose mutable arrays.

After a coordinate changes:

1. Update the block entity.
2. Call `setChanged()`.
3. Notify the currently open menu to synchronize.
4. Send a block-entity update packet containing only the six coordinate integers to the clients tracking the chunk.

When several players edit at the same time, the server commits legal payloads in order of arrival and the last commit wins.

## 6. Registration and content resources

Unified registration:

- The `export_workstation` Block.
- The `export_workstation` BlockItem.
- The `export_workstation` BlockEntityType.
- The `export_workstation` MenuType.
- The MineToMesh CreativeModeTab.

The creative tab uses the workstation block as its icon.

Survival recipe:

```text
I G I
R C R
I I I
```

- `I`: Iron Ingot
- `G`: Glass Pane
- `R`: Redstone
- `C`: Cartography Table

It produces one `export_workstation`.

Resources include the block state, a horizontally oriented model, the item model, the loot table, the pickaxe tag, the recipe and the `zh_cn` and `en_us` localizations.

## 7. Menu and network protocol

### 7.1 Menu

`ExportWorkstationMenu` contains no item slots. The client constructor receives the workstation `BlockPos` through `IContainerFactory`; the server constructor holds a `ContainerLevelAccess` and a reference to the block entity data.

The six coordinate integers are synchronized through `ContainerData`. NeoForge 1.21.1 provides full integer synchronization and does not truncate to vanilla short integers.

### 7.2 Payloads

The protocol uses a fixed version string and is registered in `RegisterPayloadHandlersEvent`.

Proposed payloads:

- `UpdateCoordinatePayload(stationPos, endpoint, axis, value)`
- `CaptureFeetPayload(stationPos, endpoint)`
- `ExportRequestPayload(stationPos, exportName)`
- `ExportGrantedPayload(stationPos, exportName, first, second, dimension)`
- `ExportRejectedPayload(stationPos, reasonKey)`

All C2S handling runs on the server main thread and validates:

- The player currently has an `ExportWorkstationMenu` open.
- The menu's bound position equals the payload position.
- The original position is still a workstation.
- The player is still within the eight-block interaction distance.
- The coordinates are a legal `BlockPos`, with Y within the current dimension's build height.
- The export name passes the existing `ExportName` safety rules and is no longer than 64 characters.

An illegal request only returns a localized rejection reason and does not disconnect the player.

"Record the block at my feet" is computed by the server from the `ServerPlayer`'s current coordinates and does not trust a player position submitted by the client.

### 7.3 Immutable export snapshot

After validation passes, the server normalizes the two points, produces an immutable snapshot containing the dimension, start point, end point and export name, and sends it to the player who started the action. The client may start the export only when the dimension still matches and the bound Screen is still open.

After the export has started, modifications of the workstation coordinates by other players do not change the job already running.

## 8. GUI specification

### 8.1 Visuals

The Screen's logical size is fixed at `384×216` GUI pixels:

- Top title bar: `x=0, y=0, width=384, height=20`.
- Left column: `x=4, y=24, width=208, height=166`.
- Right column: `x=216, y=24, width=164, height=166`.
- Bottom log bar: `x=4, y=194, width=376, height=18`.
- The outer margin and the gap between columns are both `4` GUI pixels.

Color responsibilities:

- Orange: primary actions, the start point, the active selection.
- Blue: the end point, progress, auxiliary status.
- Dark gray and mid gray: windows, panels, input boxes and structural background.

Bevel ranges:

- Outer frame bevel radius: `5` GUI pixels.
- Main panel and main button bevel radius: `4` GUI pixels.
- Input box, small button and log bar bevel radius: `2` GUI pixels.

The Minecraft implementation uses integer coordinates and nearest filtering, and does not directly copy the half-pixels or browser anti-aliasing of web CSS.

### 8.2 Coordinate editing

Each endpoint has three signed-integer input boxes, X, Y and Z.

- `Enter` or losing focus commits.
- The up/down buttons are fixed at `±1`.
- Hovering wheel `±1`.
- `Shift + wheel` is `±10`.
- When an input box has keyboard focus the wheel still adjusts, but the currently parsable text must be committed first; unparsable text stays as is and is marked red.
- A legal commit is overwritten by the value sent back from the server onto the local mirror.
- Illegal values send no payload.

The range is computed inclusive of both end blocks:

```text
sizeX = abs(x2 - x1) + 1
sizeY = abs(y2 - y1) + 1
sizeZ = abs(z2 - z1) + 1
volume = sizeX * sizeY * sizeZ
```

The computation uses overflow-safe long arithmetic. 0.3.0 sets no volume limit.

### 8.3 Export name

When the GUI opens, a default name is generated from the workstation coordinates and the current time. The player can change it; the name is not persisted to the block entity.

An illegal name marks the input box red and disables the Export button. The name is at most 64 characters.

### 8.4 State machine

Screen states:

1. `READY`: editing and export allowed.
2. `INVALID_INPUT`: illegal input is marked and export is disabled.
3. `WAITING_FOR_GRANT`: the request has been sent and operations are briefly locked.
4. `EXPORTING`: coordinates and name are locked, progress and Cancel are shown.
5. `COMPLETED`: the summary is shown; the player can close or start the next export.
6. `FAILED`: a short reason is shown.
7. `CANCELLED`: the cancellation result is shown, then restarting is allowed.

Closing the Screen during an export, pressing Esc, the block becoming invalid or being too far away all cancel the client job. Closing the Screen after normal completion does not delete the result.

## 9. Selection rendering

The selection display state belongs to each player's client session and is keyed by dimension and workstation position:

```text
OverlayKey(dimension, stationPos)
```

Behavior:

- Once turned on in the GUI, it keeps showing after the GUI closes.
- Opening the same workstation again can turn it off.
- It refreshes when the block entity synchronizes a change.
- It is cleared when the block is removed, the world is left or the dimension changes.
- It is not written to the player's persistent config and the display toggle is not broadcast.

Rendering draws only the six boundary surfaces and twelve edges:

- Orange low-alpha surfaces.
- Blue depth auxiliary edge lines.
- Depth testing and the normal depth-write policy are enabled, so solid blocks occlude it.
- The selection interior is not traversed block by block.
- Camera-relative coordinates and the current frame matrices are used to avoid far-coordinate precision drift.

## 10. Export control and progress

### 10.1 Reusing the existing pipeline

The workstation creates no second exporter. `WorkstationExportController` hands the server authorization snapshot to the existing:

```text
DefaultExportPipeline.create(...)
ExportJobManager.start(...)
```

The existing client command continues to use the same pipeline.

### 10.2 ExportTelemetry

A new thread-safe, read-only snapshot-style `ExportTelemetry` shows trustworthy stage progress in the GUI.

Overall progress weights:

- Capturing entities and sections: `0–80%`
- Draining the write queue: `80–88%`
- Writing textures and materials: `88–93%`
- Finishing glTF and OBJ: `93–97%`
- Writing the report and committing the transaction: `97–100%`

The weights express stage progress and do not claim byte-level accuracy. Progress must be monotonically non-decreasing and must show the stage name, current object, queue depth and elapsed time together.

### 10.3 Summary

After completion a client-memory `ExportSummary` is produced:

- The final status.
- The output directory.
- The number of captured objects.
- The number of primitives.
- The number of written textures.
- The number of warnings.
- The total elapsed time.
- A short failure reason.

The game does not show the full diagnostics list. The full details remain in `report.json`. The report schema is only upgraded when serialized fields are actually added, and is not force-upgraded for the Screen's in-memory summary.

### 10.4 Cancellation

Cancellation flow:

1. `ExportJobManager.cancel(reason)`.
2. Stop further capture.
3. The writer thread receives the cancellation signal.
4. `OutputTransaction` deletes the staging directory.
5. Old output directories that were already committed successfully are unaffected.

0.3.0 does not support resuming a cancelled job.

## 11. Visual assets and provenance

The confirmed design references are stored in:

```text
docs/superpowers/design-assets/minetomesh-0.3.0/
  export-workstation-dashboard.html
  gui-concept.png
  gui-greenkey-atlas.png
  workstation-block-greenkey.png
```

These files are design inputs and are not directly equivalent to production resources.

### 11.1 GUI atlas processing

1. Check that the green-screen background is strictly `#00FF00`.
2. Convert to transparent with an exact color key, without a fuzzy tolerance that would eat the edges.
3. Slice reusable widgets by rectangle.
4. Clean near-green contamination, cross-asset shadows and non-integer pixel edges.
5. Organize into a production sprite atlas with a coordinate manifest.
6. Text, digits and dynamic progress are always drawn by the game code.

If generated-image assets cannot reach the pixel-edge requirement, they are used only as visual references and redrawn by hand; sliceability must not be sacrificed to "keep the generated image".

### 11.2 Block texture processing

The final production textures are fixed as:

```text
export_workstation_front.png   16×16
export_workstation_side.png    16×16
export_workstation_back.png    16×16
export_workstation_top.png     16×16
export_workstation_bottom.png  16×16
```

Design rules:

- Front: a blue orthographic wireframe cube and an orange arrow shooting toward the upper right.
- Side: a dark gray segmented casing, short blue status slots and a few orange direction marks.
- Back: ports, a cooling grille and a blue status light.
- Top: an orange-and-blue orthographic grid with no letters.
- Bottom: a wear-resistant base plate, four corner feet and a maintenance cover.
- Lit from the upper left, darkened toward the lower right.
- Bevels are kept to 1–2 logical pixels.

Generated-image output must be remapped onto the real `16×16` logical grid and checked by hand; native pixel accuracy cannot be claimed merely from the image size.

## 12. Error handling

- Workstation not found: close the menu and show a localized error.
- Invalid player distance: reject the payload, and the menu closes according to `stillValid`.
- Coordinate out of bounds: the input box is marked red and nothing is committed.
- Illegal export name: the Export button is disabled.
- Client dimension differs from the snapshot: refuse to start.
- A job is already running: reuse the existing "already running" policy and show a summary error.
- Write failure: keep transaction atomicity, show a short reason, and put the full stack trace in the log and report.
- Corrupted block entity NBT: fall back to its own position, without making the world fail to load.

## 13. Test strategy

Strict TDD.

### 13.1 Unit tests

- The version constant and Gradle metadata are upgraded to 0.3.0.
- Block entity NBT round trip, and fallback for missing and corrupted fields.
- Coordinate normalization, size, volume and overflow handling.
- Menu data mapping and distance validity.
- Payload encoding and decoding.
- Payload menu identity, position, distance and coordinate validation.
- Feet coordinates are produced from the server-side player position.
- With several players, the last legal commit wins.
- The export snapshot is immutable.
- Input box commit, button step, wheel step and Shift step.
- Illegal input sends no network request.
- Telemetry progress is monotonically non-decreasing.
- Closing the Screen triggers cancellation.
- Summary status mapping.
- OverlayKey lifecycle and clearing policy.

### 13.2 Integration and game tests

- Registration of Block, Item, BlockEntityType, MenuType and CreativeModeTab.
- Placement facing and five-face texture mapping.
- Block saving, reloading and drops.
- Menu opening and invalidation.
- A dedicated server starts without loading client classes.
- The original command and the workstation share the export pipeline.
- All the original 0.2.0 tests keep passing.

### 13.3 Manual acceptance

- No overlapping, clipped or unclickable controls at GUI scales 2, 3 and 4.
- The appearance is correct in all four horizontal facings.
- Two players open and edit the same workstation at the same time.
- Buttons, wheel, Shift wheel and feet picking are correct.
- The translucent selection volume is occluded normally by world geometry.
- Closing the GUI cancels the export and cleans staging.
- Create, Touhou Little Maid and ordinary block export regression.
- Pure-white vertex colors in Blender still produce no extra alpha multiplication node.

## 14. Documentation and release

- `gradle.properties` and `McGltf.VERSION` are updated to `0.3.0`.
- `McGltf.DISPLAY_NAME` is updated to `MineToMesh`.
- The README is updated with the dual-side installation requirement, workstation operation, the command fallback, the recipe and the export cancellation rules.
- The manual acceptance matrix gains workstation, multiplayer, GUI scale, Overlay and dedicated server items.
- The build artifact is `mcgltf-0.3.0.jar`.
- Before the final release, verify that the production JAR does not contain the testmod and the original design images.

## 15. References

- NeoForge 1.21.1 Menus: `https://docs.neoforged.net/docs/1.21.1/gui/menus/`
- NeoForge 1.21.1 Payloads: `https://docs.neoforged.net/docs/1.21.1/networking/payload/`
- Create 1.21.1 `AllGuiTextures` is only used to understand how compact mechanical GUIs are segmented; all production resources are original, and MineToMesh adds no Create dependency.

## 16. Completion criteria

0.3.0 is considered complete only when all of the following are met:

- Dual-side registration and dedicated-server safety pass.
- The workstation can be crafted, placed, saved, opened and synchronized in multiplayer.
- All confirmed GUI interactions work.
- The selection display lifecycle and depth occlusion are correct.
- Workstation export reuses the existing pipeline and keeps transaction atomicity.
- Closing the GUI reliably cancels.
- The summary and progress fabricate no data.
- The production GUI and block textures pass pixel-level manual inspection.
- The full automated test suite passes.
- The user completes acceptance with real mods and Blender scenes.
