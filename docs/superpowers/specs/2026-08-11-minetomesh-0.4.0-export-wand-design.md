# MineToMesh 0.4.0 Export Wand Design

Status: design confirmed  
Date: 2026-08-11  
Target version: 0.4.0  
Minecraft: 1.21.1  
NeoForge: 21.1.244

## 1. Goals

MineToMesh 0.4.0 replaces the region export workstation with a portable "Export Wand". Each wand stores one selection independently; players can quickly set `pos1` and `pos2` in the world in a WorldEdit style, then open the export GUI with Shift + right click and run the existing glTF and OBJ export pipeline.

This release is a breaking replacement: the registry entries, gameplay entry points, code and resources of the region export workstation are all hard-deleted, with no legacy compatibility shim for old worlds.

## 2. Confirmed product rules

- Each wand owns its own selection; no per-player global state or storage outside the world is used.
- Left click on a block sets `pos1` and completely prevents block breaking and cracks.
- Right click on a block sets `pos2` and completely prevents the block's own interaction.
- Shift + right click has the highest priority and only opens the GUI, whether it hits air or a block.
- Shift + left click on air clears the current wand's selection directly.
- Shift + left click on a block still sets `pos1` and does not clear.
- Setting an endpoint across dimensions is rejected and the original selection is kept.
- The GUI coordinate boxes stay editable and keep the existing stepping and wheel fine-tuning.
- The two "feet" button groups are deleted, and no clear button is added to the GUI.
- The selection renders only while the matching wand is held and that wand has display enabled.
- World point setting, clearing and dimension rejection use sound feedback only, with no chat or action-bar text.
- A dedicated server still only lets OPs or administrators with vanilla command permission level 2 or higher run exports.
- Ordinary players can still craft the wand, set selections, open the GUI and see the Overlay.
- The integrated singleplayer server ignores the export permission.
- A new wand or an incomplete selection can still open the GUI; the Export button stays disabled until the selection is complete.

## 3. Scope

### 3.1 Included

- The new item `mcgltf:export_wand`.
- A custom ItemStack Data Component.
- Left click block, right click block, Shift + left click air and Shift + right click interactions.
- An item menu and Screen that do not depend on a block entity.
- Server-authoritative modification and synchronization of wand data.
- A selection Overlay driven by the held wand.
- A 16×16 engineering measuring-rod item texture.
- A craftable recipe and a creative-mode tab entry.
- Item-based rework of the existing export authorization, progress and cancellation chain.
- Complete removal of the region export workstation.

### 3.2 Excluded

- Multi-selection lists, named selection libraries or history.
- Wand durability, enchantments, upgrades or quality tiers.
- Cross-dimension selections.
- Showing the Overlay of multiple wands at the same time.
- A permission configuration UI for ordinary players on dedicated servers.
- Migration of old workstation saves or Missing Mapping repair.
- New export formats or changes to the export algorithm.

## 4. Version and compatibility

The version is raised to `0.4.0`. The build artifact is:

```text
mcgltf-0.4.0.jar
```

0.4.0 removes the block, item, block entity and menu registrations of `mcgltf:export_workstation`. Saves containing old workstations may show missing registry entries, lost blocks or load warnings; this risk has been explicitly accepted.

The existing glTF, OBJ, texture capture, progress statistics, transaction publishing and close-GUI-cancels semantics are unchanged.

## 5. Domain model and Data Component

### 5.1 `ExportWandSelection`

The wand stores its data in a typed custom Data Component:

```text
wandId: Optional<UUID>
selectionDimension: Optional<ResourceLocation>
pos1: Optional<BlockPos>
pos2: Optional<BlockPos>
overlayEnabled: boolean
exportName: String
```

Constraints:

- The maximum item stack size is 1.
- `wandId` is initially empty, is generated on first server-side use, and is saved and synchronized with the ItemStack.
- `pos1` and `pos2` each use a complete `BlockPos` as the smallest persistence unit.
- When either endpoint exists, `selectionDimension` must exist.
- Both endpoints may only belong to the single dimension given by `selectionDimension`.
- When neither endpoint exists, `selectionDimension` must be empty.
- `overlayEnabled` defaults to `true`.
- `exportName` defaults to `export`.
- The Data Component must provide both a persistence Codec and a network StreamCodec.

Creative-mode copying may duplicate the same `wandId`. Menu identity verification binds the hand, the slot and the `wandId` together, so a cloned item never gains modification rights over the current menu from a different slot.

### 5.2 `ExportWandService`

All wand data writes are centralized in the server-side service layer:

- `ensureIdentity(stack)`
- `setEndpoint(player, hand, endpoint, blockPos)`
- `clearSelection(player, hand)`
- `updateEndpoint(player, binding, endpoint, blockPos)`
- `setOverlayEnabled(player, binding, enabled)`
- `setExportName(player, binding, exportName)`
- `snapshotForExport(player, binding)`

The service layer is responsible for item identity, dimension, build height, export name and permission validation. A failed operation must never partially modify the Data Component.

## 6. Input priority and interaction

| Input | Target hit | Behavior | Vanilla behavior |
|---|---|---|---|
| Left click | Block | Set `pos1` | Cancel breaking and cracks |
| Shift + left click | Block | Set `pos1` | Cancel breaking and cracks |
| Left click | Air | No wand action | Stays without result |
| Shift + left click | Air | Clear the selection | Executes only once per key press |
| Right click | Block | Set `pos2` | Cancel block interaction |
| Right click | Air | No wand action | Does not open the GUI |
| Shift + right click | Block or air | Open the export GUI | Does not set `pos2`, does not trigger the block |

### 6.1 Left click on a block

The NeoForge left-click-block event is used to cancel breaking on both client and server. The server reads the block position the player actually hit and updates the currently held wand; the client is only responsible for blocking the visual breaking immediately.

### 6.2 Right click

The block-use and air-use logic of `ExportWandItem` share the Shift-first rule:

- When Shift is true, request the server to open the wand menu.
- When Shift is not pressed and a block is hit, set `pos2` and consume the interaction.
- When Shift is not pressed and air is hit, return no-op.

### 6.3 Shift + left click on air

Minecraft has no equivalent server-side block-hit event. The client listens to the attack key mapping trigger and sends a clear request only for the combination "Shift, wand in hand, crosshair not on a block". The server re-validates the wand currently in hand before clearing. Holding the attack key must not repeat the clear every tick.

## 7. Dimension and coordinate rules

- When either endpoint is first set, the current dimension is written to `selectionDimension`.
- Later endpoint settings must happen in the same dimension.
- A cross-dimension request is rejected and neither the original dimension nor the endpoints change.
- A clear operation removes the dimension, `pos1` and `pos2` together.
- A clear operation keeps `overlayEnabled` and `exportName`; because the endpoints are empty, the Overlay naturally has no geometry to render.
- An unset endpoint is shown in the GUI as three empty input boxes.
- The Screen may keep incomplete text locally; a complete endpoint is committed only when all of X/Y/Z are valid integers.
- The server validates that the Y value lies within the current dimension's build height range.
- Volume calculation keeps using overflow-safe `long` arithmetic and sets no hard limit.

## 8. Sound feedback

World interaction sends no action-bar or chat text.

- `pos1` set successfully: `minecraft:block.note_block.hat`, pitch `0.75`.
- `pos2` set successfully: `minecraft:block.note_block.hat`, pitch `1.25`.
- Clear succeeded: `minecraft:block.beacon.deactivate`, pitch `1.0`.
- Cross-dimension or illegal operation: `minecraft:entity.villager.no`, pitch `1.0`.
- GUI opened: `minecraft:item.book.page_turn`, pitch `1.1`.

The volume is uniformly `0.6` and plays only to the acting player. All of them reuse vanilla SoundEvents; no custom audio assets are added.

## 9. Item menu and GUI

### 9.1 `ExportWandMenu`

The menu is opened on the server and binds:

```text
hand
inventorySlot
wandId
```

`stillValid` must verify that the bound slot still holds `mcgltf:export_wand` with a matching `wandId`. The menu becomes invalid when the wand is moved, replaced, dropped or no longer in the bound slot.

When the menu is invalidated or closed:

- Cancel the waiting-for-grant state owned by this Screen.
- Cancel the export job started by this Screen that has not yet terminated.
- Do not delete export directories that have already been published successfully.

### 9.2 `ExportWandScreen`

Keeps the existing 384×216 GUI frame and nine-slice rendering policy:

- The title becomes "Export Wand".
- The two "feet" buttons are deleted.
- The coordinate input box width grows from about 82px to about 116px.
- Keeps the six coordinate inputs, up/down stepping, wheel `±1` and Shift + wheel `±10`.
- Keeps the selection display toggle.
- Keeps the export name, Export, Cancel, progress, summary and the bottom status area.
- No GUI clear button is added.
- The fixed nine-slice border continues to convert the GUI scale at 8 physical screen pixels.
- Fixed corners are not stretched, edges stretch along a single axis, and the safe center stretches along both axes.

A new wand or an incomplete selection is allowed to open the GUI. The Export button is enabled only when both endpoints are complete, the dimension is valid, the export name is legal and there is no active job.

### 9.3 Coordinate and export payloads

All GUI writes and export requests carry the menu binding identity. The server reads the real data from the currently bound ItemStack and does not accept a complete export snapshot submitted by the client as the authoritative source.

The export Grant contains the following server-frozen values:

```text
wandId
selectionDimension
pos1
pos2
exportName
```

The client starts the existing export pipeline only when the current Screen is still open, the menu binding matches and the dimension is consistent.

## 10. Selection Overlay

The Overlay checks the player's current main hand and off hand every frame:

- Only an Export Wand actually held is chosen.
- `overlayEnabled` must be true.
- `pos1`, `pos2` and the dimension must be complete and match the current client dimension.
- Only one wand's selection is rendered at a time; the main hand takes priority over the off hand.
- Switching to another item hides it immediately.
- Picking the same wand up again restores it from its Data Component.

The visuals keep the existing rules: an orange translucent volume, blue auxiliary edge lines and normal depth occlusion.

## 11. Permissions

Permission only restricts "starting an export" and does not restrict crafting, point setting, opening the GUI or viewing the Overlay.

- Integrated singleplayer server: allowed.
- Dedicated server: requires `player.createCommandSourceStack().hasPermission(2)`.
- Insufficient permission: the server returns a localized rejection reason, the GUI shows a failed status and no client export job is started.

## 12. Visuals and resources

### 12.1 Item visuals

The item texture is a 16×16 pixel engineering measuring rod:

- A metallic gray body.
- Orange represents `pos1`.
- Blue represents `pos2`.
- The silhouette emphasizes the semantics of a surveying instrument, a ruler and an engineering tool.
- No high-fantasy crystal style is used.

Resource paths:

```text
assets/mcgltf/models/item/export_wand.json
assets/mcgltf/textures/item/export_wand.png
```

### 12.2 GUI resource migration

The 77 approved individual slices are migrated to:

```text
assets/mcgltf/textures/gui/export_wand/gui_001.png
assets/mcgltf/textures/gui/export_wand/gui_002.png through gui_076.png
assets/mcgltf/textures/gui/export_wand/gui_077.png
```

The texture constants class is renamed `ExportWandTextures`. The production UI must not reference the old whole-image `textures/gui/export_workstation.png`.

## 13. Crafting and creative tab

The exact shaped recipe:

```text
_ _ A
_ R C
S _ _
```

JSON pattern:

```json
[
  "  A",
  " RC",
  "S  "
]
```

Ingredients:

- `A`: `minecraft:amethyst_shard`
- `R`: `minecraft:redstone`
- `C`: `minecraft:copper_ingot`
- `S`: `minecraft:stick`

Result:

```text
1 × mcgltf:export_wand
```

The wand is not stackable, has no durability and offers no enchantability. The MineToMesh creative tab shows only the Export Wand and uses the wand as its icon.

## 14. Workstation hard-deletion list

Delete the following registrations and implementations:

- The `mcgltf:export_workstation` block.
- The `mcgltf:export_workstation` BlockItem.
- The `mcgltf:export_workstation` block entity type.
- The `mcgltf:export_workstation` menu type.
- `ExportWorkstationBlock`.
- `ExportWorkstationBlockEntity`.
- `ExportWorkstationMenu`.
- Workstation-specific request, coordinate sync and feet capture payloads.
- Workstation-specific Screen, Controller and texture classes; the old classes are deleted after their functionality has been migrated.
- The workstation block state, block model, item model and five-face block textures.
- The workstation loot table, crafting recipe and mining tag resources.
- The workstation language keys.
- `textures/gui/export_workstation.png`.
- The old `textures/gui/workstation/` directory.

Reusable pure value objects or rendering algorithms are kept after being renamed and detached from the workstation, for example the coordinate editor model, the nine-slice border policy and the Overlay geometry logic.

## 15. Error handling

- A request without a held wand: ignored, or an invalid-item rejection is returned.
- Slot, hand or `wandId` mismatch: the menu is invalidated and modification is rejected.
- Cross-dimension point setting: play the rejection sound and leave the data unchanged.
- Illegal coordinate text: keep the local draft and mark it red, and do not send a partial endpoint.
- Build height out of range: the server rejects it and the GUI shows a localized failure reason.
- Incomplete selection: the Export button is disabled; the server still performs a defensive rejection.
- Insufficient permission: return `mcgltf.error.wand.no_export_permission`.
- An active job already exists: return an already-running rejection.
- Screen closed, menu invalidated, leaving the world, changing dimension or resource reload: cancel jobs that have not yet terminated.
- The network reply handlers stay resident for the process lifetime and must not be reset to empty functions when leaving the world.

## 16. Test design

### 16.1 Unit and integration tests

- Round trip of the Data Component persistence Codec and StreamCodec.
- New wand defaults and first-time identity generation.
- Single endpoint, complete selection, clearing and volume calculation.
- Cross-dimension rejection with the original data unchanged.
- Left click on a block cancels breaking and sets `pos1`.
- Right click on a block cancels interaction and sets `pos2`.
- Shift input priority.
- Shift + left click on air clears exactly once.
- The GUI binds hand, slot and `wandId`.
- The menu is invalidated after moving, replacing or dropping the wand.
- GUI coordinate drafts are written back to the ItemStack once all three axes are complete.
- Data isolation between multiple wands.
- Main hand priority, and Overlay display only while held.
- Singleplayer allowed, dedicated-server administrator allowed, ordinary player rejected.
- Recipe, item model, 16×16 texture, creative tab and language resources.
- The 77 GUI slices exist and their hashes are traceable.
- Workstation registrations, classes, payloads and resources are completely absent.
- Client classes do not leak into the dedicated server load path.
- Grant/Reject replies can still be handled after leaving and re-entering a world.

### 16.2 Manual acceptance

1. Left click on a block shows no cracks and does not break it, and `pos1` is set.
2. Right click on a chest, door, button or other interactive block only sets `pos2`.
3. Shift + right click on both air and blocks opens the GUI.
4. Shift + left click on air clears; Shift + left click on a block still sets `pos1`.
5. After binding in the Overworld, setting a point in the Nether is rejected and the original selection is unchanged.
6. Two wands save different selections and export names.
7. The Overlay hides and restores instantly when switching the held item.
8. After the GUI is open, moving, replacing or dropping the wand closes the menu automatically and cancels the job.
9. The grant reply works normally after leaving and re-entering the world.
10. glTF and OBJ exports actually complete and can be opened in Blender.
11. A dedicated server starts without linking client classes.
12. Ordinary players cannot export, while administrators and singleplayer can.

## 17. Release acceptance

0.4.0 may be delivered only when all of the following are satisfied:

- `clean test build` is fully green.
- The dedicated-server class isolation test is fully green.
- `runServerSmoke` prints `MINETOMESH_SERVER_READY` and shows MineToMesh 0.4.0.
- The production JAR is named `mcgltf-0.4.0.jar`.
- The `neoforge.mods.toml` inside the JAR has version `0.4.0` and side BOTH.
- The JAR contains the Export Wand item model, texture, recipe and the 77 GUI slices.
- The JAR contains no testmod, design source files, `.superpowers`, old workstation classes or old workstation resources.
- The complete loop of "enter a world, quit, re-enter, set points, open the GUI, export" is completed on a real client.
