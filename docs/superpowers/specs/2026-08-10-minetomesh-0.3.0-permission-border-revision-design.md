# MineToMesh 0.3.0 Export Permission and Physical Border Revision Design

Date: 2026-08-10  
Status: the user has chosen permission option A and border option A

## 1. Scope

This revision changes only two behaviors:

1. The export authorization permission of the region export workstation.
2. The fixed border unit of the stretchable GUI assets.

Coordinate editing, feet-position picking, selection display, the export pipeline, workstation persistence and the existing client commands are out of scope.

## 2. Export permission

### 2.1 Behavior

- Ordinary players can still open the workstation, edit the shared coordinates, use feet-position picking and toggle the selection display.
- The permission check runs only when handling `ExportRequestPayload`.
- When a local singleplayer world runs on the integrated server, the current player is unconditionally allowed to export, without requiring cheats to be enabled.
- A dedicated server or a non-singleplayer integrated environment requires the player to have vanilla command permission level `2` or higher.
- Vanilla OPs and administrators mapped to command level `2+` by a permission system both pass.
- A request without permission makes the server send `ExportRejectedPayload`, with the reason key fixed to `mcgltf.error.workstation.no_export_permission`.
- The client must never grant permission itself; the final decision exists only in the server payload handling chain.

### 2.2 Validation order

1. The payload sender must be a `ServerPlayer`.
2. Menu identity, workstation existence and the eight-block distance check stay unchanged.
3. Run the export permission check.
4. Only after the permission passes, validate the export name and coordinate bounds.
5. After everything passes, send the immutable `ExportGrantedPayload`.

A permission rejection does not change the workstation coordinates, does not start a client export job and does not create a transaction directory.

## 3. Fixed 8px physical border

### 3.1 Unit

The `8px` specified by the user means physical screen pixels, not Minecraft logical GUI pixels. At runtime it is converted according to the current GUI scale:

```text
targetLogicalBorder = round(8 / guiScale)
```

The result is at least `1` logical pixel. Because the Minecraft GUI uses integer logical pixels, when the GUI scale does not divide 8 evenly the nearest integer is used; for example scale 3 uses 3 logical pixels, which is 9 physical pixels.

### 3.2 Asset safety constraint

Every nine-slice draw must also limit the border by the source texture and the destination control size:

```text
safeBorder = min(
  targetLogicalBorder,
  floor((sourceWidth - 1) / 2),
  floor((sourceHeight - 1) / 2),
  floor((destinationWidth - 1) / 2),
  floor((destinationHeight - 1) / 2)
)
```

At least a `1×1` pixel is always kept for the center region, so the top and bottom borders can never consume the whole 16px-tall asset.

### 3.3 Nine-slice rules

- The four corners use the original source pixels.
- The top and bottom edges stretch only along the X axis.
- The left and right edges stretch only along the Y axis.
- The safe center stretches along both axes.
- Source UVs must not go out of bounds, decorative pixels are not tiled, and the texture is never scaled as a whole.
- Export, Cancel and the selection toggles keep a control height of 16 logical pixels; the visual border width is determined by the GUI scale conversion policy.
- The selection toggle base plate is fixed and not swapped, and `GUI_059` and `GUI_060` are overlaid at their original size as same-size state cores.

## 4. Tests

### 4.1 Permission policy unit tests

Cover:

- Local singleplayer world with permission level 0: allowed.
- Dedicated server with permission level 0/1: rejected.
- Dedicated server with permission level 2/3/4: allowed.
- The rejection reason key is stable.
- The permission check is wired only to the export payload and does not affect coordinate updates or feet-position picking.

### 4.2 Border policy unit tests

Cover:

- Logical border conversion at GUI scales 1/2/3/4/6.
- A 16px-tall asset always keeps at least a 1px center.
- A small destination control is never consumed by the border.
- The Screen contains no whole-image scaling or decorative-region tiling path.

### 4.3 Regression verification

- Focused workstation tests.
- `clean test build`.
- `ServerClassIsolationTest` and `runServerSmoke`.
- In-game checks of the Export, Cancel, selection toggles and input-box borders at GUI scales 2/3/4.
