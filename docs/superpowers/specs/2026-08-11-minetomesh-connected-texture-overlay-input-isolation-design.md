# MineToMesh Connected Textures, Grass Block Overlay and Input Isolation Design

Date: 2026-08-11  
Status: design confirmed, awaiting the user's written review  
Baseline branch: `feature/mcgltf-0.4.0-export-wand`  
Target version: 0.5.0  
Minecraft: 1.21.1  
NeoForge: 21.1.244

## 1. Background

The current candidate version exposed three kinds of problems in a real Create 6.x scene:

1. Create blocks use continuous textures in the game, but after export they degrade to a single-block texture repeated on every block.
2. The vanilla grass block side exports two layers of coplanar geometry, making it hard in Blender to separate the transparent grass-color overlay from the body for editing.
3. While the export name input box is focused, pressing character keys such as `E` still triggers the inventory or other game hotkeys; Chinese input methods are affected by the same physical key leak.

The goal of this round is, without introducing a hard Create dependency and without sacrificing the streaming export boundary, to capture the connected texture UVs the game has already computed; to keep the original two-layer structure of the grass block while merging the side Overlay of the whole selection into one independent object; and to completely isolate game hotkeys while the wand GUI is open.

Visual correctness is accepted manually by the project owner in the real game and in Blender. Automation only verifies what can be decided mechanically: texture identity, UVs, object structure, material attributes, keyboard events, document validity and build results.

## 2. Confirmed goals

### 2.1 Must implement

- Connected-texture export compatible with Minecraft 1.21.1, NeoForge 21.1.244 and Create 6.x.
- No compile-time types from Create, Catnip or Flywheel are referenced, and no matching runtime dependency is added.
- When the Sprite declared by a `BakedQuad` disagrees with the actual atlas UVs of its vertices, look up the real Sprite from the same `TextureAtlas`.
- Create blocks should export their `create:block/*_connected` texture sheets and keep the sub-cell UVs the game has already chosen.
- Ordinary blocks, animated textures and existing resource pack overrides keep their original export behavior.
- Keep the two layers of geometry of the vanilla grass block: the base side layer and the transparent tinted Overlay.
- Merge the `minecraft:block/grass_block_side_overlay` of the whole selection into a single independent object.
- The Overlay keeps its transparent material, the biome-tint vertex colors, the original world coordinates and the original quads.
- While the wand GUI is open, only Esc on the keyboard can close the interface; the mouse Cancel button stays effective, and game and mod hotkeys must not receive keyboard events.
- Latin characters, characters committed by Chinese input methods, digits, the minus sign and common text-editing key combinations work normally inside the input box.
- Enter only commits the current input box and does not start the export directly; Tab only switches GUI focus.
- Failure and cancellation continue to follow the existing `OutputTransaction` cleanup rules.

### 2.2 Explicitly not done

- Create's connection logic, `CTType` or `CTSpriteShiftEntry` algorithms are not re-implemented.
- Create's private fields are not read through reflection.
- The whole block atlas is not exported by default.
- The grass base image and the Overlay are not baked into a single texture.
- Each grass block Overlay is not split into its own object.
- Not all transparent materials or all block materials are split into global objects.
- Hotkeys are not blocked by clearing the global `KeyMapping` state.
- Screenshot similarity, automatic visual scoring or automatic visual pass decisions do not replace manual acceptance.

## 3. Root cause analysis

### 3.1 Why Create connected textures degrade

Create 1.21.1's `CTModel` first reads the real neighboring blocks of the world in `getModelData` to produce a connected-texture index for each face; then in `getQuads` it clones the original `BakedQuad` and rewrites the four vertex UVs into one sub-cell of the target `*_connected` Sprite.

The key detail is that `BakedQuadHelper.clone` keeps the original `quad.getSprite()`. The final Quad therefore contains both:

```text
declared Sprite: the original single-block texture
actual UVs: the region of the target connected texture sheet in the block atlas
```

The game samples directly with the atlas UVs, so it displays correctly. The current `BlockModelExtractor` first trusts `quad.getSprite()`, extracts the original single-block PNG, and then normalizes the UVs already rewritten by Create against that Sprite's bounds. The normalized result lands outside the single-block texture, and after the material samples it repeatedly it shows up as separate individual blocks.

Reference implementations:

- Create `CTModel.java`: `https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/foundation/block/connected/CTModel.java`
- Create `CTSpriteShiftEntry.java`: `https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/foundation/block/connected/CTSpriteShiftEntry.java`
- Create `BakedQuadHelper.java`: `https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/foundation/model/BakedQuadHelper.java`

### 3.2 Why the grass block has an extra side layer

The vanilla `grass_block.json` of Minecraft 1.21.1 explicitly contains two cube elements of the same size:

- The four side faces of the first layer use `minecraft:block/grass_block_side`.
- The four side faces of the second layer use `minecraft:block/grass_block_side_overlay` with `tintindex: 0`.

The game relies on transparent clipping and biome tinting to composite the two layers into the final side. The exporter currently captures the two coplanar quads faithfully, so Blender sees the second layer of geometry directly. This is not a duplicate-capture bug, but the vanilla model structure being fully exposed.

Vanilla resource: `https://assets.mcasset.cloud/1.21.1/assets/minecraft/models/block/grass_block.json`

### 3.3 Why the input box triggers the E-key inventory

The current `ExportWandScreen.keyPressed` calls `super.keyPressed` for keys that are neither Enter nor Esc. The call chain enters `AbstractContainerScreen.keyPressed`, which checks `minecraft.options.keyInventory`; the text write of the character `E` happens in the later `charTyped`, so `EditBox.keyPressed(E)` normally does not consume the physical key, and the container layer then runs `onClose()`.

Even returning `true` after calling `super.keyPressed` is already too late in the current implementation, because the closing action has happened. Input isolation must be completed before entering the hotkey branch of `AbstractContainerScreen`.

## 4. Approach comparison

### 4.1 Look up the Sprite from the actual UVs, adopted

From the actual atlas UVs of the Quad's four vertices, find the real Sprite that fully covers that UV region among the registered Sprites of the same `TextureAtlas`.

Advantages:

- Does not depend on Create types.
- Compatible with other mods that use Sprite Shift but keep the old `BakedQuad.sprite` metadata.
- Continues to output editable standalone PNGs.
- Keeps the connection indices the game has already computed, without copying the mod's algorithm.

Cost: a cacheable atlas spatial index has to be built, and floating-point boundaries have to be handled.

### 4.2 Whole-atlas fallback, not adopted

For abnormal Quads, export the whole block atlas and keep the original UVs.

The advantage is that it is closest to the GPU's actual sampling. The cost is a huge texture, bloated output, materials that are hard to edit in Blender, and breaking the current standalone-Sprite output convention.

### 4.3 Create-specific adapter, not adopted

Obtain the target Sprite directly through Create or Catnip classes.

This route is precise for a single version, but it is tightly coupled to internal classes, method signatures and load order, and would turn the generic exporter into a collection of Create special cases.

## 5. Overall architecture

Three new components with clear responsibilities:

```text
BakedQuad
  → capture the raw vertices and actual atlas UVs
  → AtlasSpriteResolver
  → SpriteTextureExtractor
  → BlockPrimitiveRouter
      → Section body stream
      → Selection-global grass side Overlay stream
  → StreamingSceneSession
      → glTF Writer
      → OBJ Writer
```

GUI input follows its own path:

```text
GLFW keyPressed / keyReleased / charTyped
  → ExportWandScreen input isolation
      → current EditBox
      → GUI focus navigation
      → Esc closes
  × does not enter the game KeyMapping
```

## 6. `AtlasSpriteResolver`

### 6.1 Input and output

Input:

```text
declaredSprite
the four actual atlas UVs
canonical sprites in the TextureAtlas
```

Output:

```text
resolvedSprite
resolutionKind = DECLARED | REDIRECTED | FALLBACK
optional diagnostic
```

The resolution result uses the canonical `TextureAtlasSprite` and no longer uses temporary wrapper Sprites of mods.

### 6.2 Spatial index

Each Atlas builds only one `AtlasSpriteIndex` during an export:

- Read the stable resource ID and canonical Sprite from `TextureAtlas.getTextures()`.
- Use a fixed 256×256 bucket index in normalized UV space, to avoid a linear scan of the whole atlas for every Quad.
- A Sprite is inserted into all buckets it covers; a query uses the center bucket of the bounding box of the four UVs, then performs an exact coverage check.
- The index is cached by Atlas identity and rebuilt after a resource reload or for the next export.

The fixed buckets are only used to narrow the candidate set and do not take part in the final decision.

### 6.3 Coverage and selection rules

1. First get the canonical declared Sprite in the Atlas through `declaredSprite.contents().name()`.
2. If the canonical declared Sprite covers all four UVs within tolerance, return `DECLARED`.
3. Otherwise query candidate Sprites that cover all the UVs.
4. When the candidate is unique, return that Sprite.
5. With several candidates, choose the one with the smallest UV area; on equal areas choose stably by lexicographic resource ID.
6. With no candidate, return the canonical declared Sprite and record `ATLAS_SPRITE_RESOLUTION_FAILED`.

The tolerance is converted from the Atlas size derivable from the candidate Sprite, with a maximum of half an Atlas pixel. The tolerance is only used for boundary comparison and never modifies the final UVs.

### 6.4 Capture order adjustment

`BlockModelExtractor.captureQuad` must first obtain the real UVs through `CapturingVertexConsumer` and then resolve the Sprite. The later steps change to:

```text
capture raw vertices
→ resolve actual sprite from raw UVs
→ extract resolved sprite image
→ normalize raw UVs against resolved sprite bounds
→ resolve material
→ append routed primitive
```

Create's target is the whole `*_connected` Sprite. The complete texture sheet is exported, and the normalized UVs still stay within the sub-cell Create has already chosen.

### 6.5 Caching

- `SpriteTextureExtractor.Extraction` is cached by canonical Sprite identity.
- `TextureRegistry` continues to deduplicate by stable `TextureKey`.
- Redirect diagnostics are deduplicated by the combination "declared resource ID → target resource ID", so the report is not flooded for every block.

## 7. Grass block Overlay routing

### 7.1 Classification rule

A new `BlockPrimitiveRouter` is added. Classification relies only on the resolved stable texture resource ID:

```text
minecraft:block/grass_block_side_overlay
    → GLOBAL_GRASS_SIDE_OVERLAY
other textures
    → SECTION_BLOCKS
```

It does not guess from transparency, tint, pixel content or geometric coplanarity, to avoid wrongly splitting materials of other mods.

### 7.2 Object contract

The whole selection produces at most one object:

```text
selection/grass_side_overlay
```

Its `CapturedNode.Kind` uses the new `OVERLAY` and writes:

```json
{
  "layerRole": "grass_side_overlay",
  "scope": "selection",
  "sourceTexture": "minecraft:block/grass_block_side_overlay"
}
```

No empty object is created when there is no grass block Overlay.

### 7.3 Material and geometry

- Uses the original `grass_block_side_overlay` RGBA texture.
- Keeps the Alpha Mode and double-sided policy derived from the RenderType.
- Keeps the biome vertex colors produced by `tintindex: 0`.
- Keeps the original quad topology, normals, world coordinates and UVs.
- The body grass block still keeps `grass_block_side`, the top face and the bottom face.
- The Overlay is not counted as a placeholder and does not change the scanned-block and rendered-block counts.

### 7.4 Streaming writes

The user chose one Overlay object for the whole selection, so the Writer must merge a logical object across Sections while avoiding hoarding all grass vertices inside the capture thread.

#### glTF

- Each Section can submit a same-named `OVERLAY` node fragment.
- `StreamingGltfSession` reuses the same Node/Mesh by a stable object key.
- The binary vertices and indices of each batch of Primitives are still written to `.bin` immediately.
- The document builder only keeps appending Primitive descriptions and does not retain historical vertex objects.

#### OBJ

- `StreamingObjSession` writes Overlay Primitives into a temporary fragment inside the output transaction.
- The temporary fragment uses standard OBJ negative relative indices, so it does not depend on the final body vertex offset.
- At `finish()` it writes `o selection_grass_side_overlay` only once and then appends the fragment.
- The temporary fragment is deleted afterwards; on cancellation or exceptions `OutputTransaction` cleans it up.

This way only one grass side Overlay object appears in Blender, while keeping the bounded-memory property of large-selection exports.

## 8. GUI keyboard and input method isolation

### 8.1 Basic rules

- `ExportWandScreen.passEvents = false`.
- Esc directly calls the Screen close logic and returns `true`.
- Enter or keypad Enter only commits the currently focused coordinate endpoint or export name and returns `true`.
- Tab only calls GUI focus navigation; whatever the parent class returns, the Screen finally returns `true`.
- Other physical keys must not enter the inventory, hotbar, drop or mod hotkey branches of `AbstractContainerScreen`.

### 8.2 Text box routing

When an input box is focused:

- `keyPressed` directly calls the current `EditBox.keyPressed` and then unconditionally returns `true`.
- `keyReleased` directly calls the currently focused widget and then unconditionally returns `true`.
- `charTyped` directly calls the current `EditBox.charTyped` and then unconditionally returns `true`.

This lets Ctrl+A/C/V/X, Backspace, Delete, the arrow keys, Latin characters and characters committed by Chinese input methods act on the input box. Even if `EditBox` reports the `keyPressed` of a character key as unhandled, the Screen must consume it; the real text is still written through the later `charTyped`.

### 8.3 No input box focus

Except for Esc and Tab, E, WASD, the number hotbar keys, Q, F, mod hotkeys and character events all return `true`, and change no player, container, inventory or global key state.

After the GUI closes no `KeyMapping` is modified, and Minecraft restores game input normally on the next frame.

## 9. Diagnostics and error handling

New diagnostic codes:

- `ATLAS_SPRITE_REDIRECTED`: INFO, records the declared Sprite and the real Sprite.
- `ATLAS_SPRITE_RESOLUTION_FAILED`: WARNING, records the Atlas, the declared Sprite and the UV bounding box.
- `GLOBAL_OVERLAY_SPOOL_FAILED`: FAILURE, records the temporary fragment stage and the exception.

Rules:

- A successful Sprite redirect does not turn the export into `completed_with_warnings`.
- A failed lookup keeps the declared Sprite and continues exporting other objects.
- When the classification of a single Overlay Quad fails, it stays in the Section body and the geometry is not discarded.
- A failed write of the Overlay temporary fragment is an output transaction failure, and no incomplete directory is published.
- A glTF Overlay merge key conflict, Kind mismatch or Extras mismatch fails, and nodes with different semantics are never silently merged.
- GUI input isolation does not catch or swallow system-level window close events.

## 10. Automated test design

### 10.1 Sprite resolution

- A declared Sprite that fully covers the four UVs returns `DECLARED`.
- Create-style fixture: a declared ordinary Sprite with UVs pointing to `*_connected` returns the target Sprite.
- When the target is a 2×2, 4×4 or 8×8 texture sheet the correct local UVs are kept.
- The half-pixel boundary tolerance does not misjudge into an adjacent Sprite.
- With several candidates the smallest area is chosen, and ties are broken stably by resource ID.
- With no candidate the declared Sprite is returned and one warning is produced.
- When the same redirect repeats, INFO is recorded only once.
- Ordinary animated Sprites keep the existing first-frame and animation sidecar behavior.

### 10.2 Block capture and Overlay

- A test BakedModel returns a Quad with "old Sprite metadata + target Atlas UVs"; the exported material must reference the connected TextureKey.
- Material and UV regression for ordinary blocks is unchanged.
- `grass_block_side` stays in the Section body.
- `grass_block_side_overlay` goes into the `OVERLAY` route.
- The Overlay keeps MASK/BLEND, the Tint vertex colors and the quad source topology.
- No empty Overlay is created when there are no grass blocks.

### 10.3 Writers

- Same-named Overlays from several Sections produce only one Node/Mesh in glTF.
- `o selection_grass_side_overlay` appears only once in OBJ.
- All faces, materials and vertices of the Overlay exist.
- The OBJ negative relative indices import with a legal structure.
- The Overlay temporary fragment does not exist after success, cancellation and exceptions.
- glTF keeps passing the internal validation, and the OBJ/MTL path stays valid.

### 10.4 GUI input

- With the name box focused, the `keyPressed` of `E` does not close the GUI, and the following `charTyped('e')` writes the text.
- Chinese characters are written through `charTyped` and accepted by `ExportName`.
- The coordinate box accepts digits, the minus sign, Backspace and the arrow keys.
- Ctrl+A/C/V/X act only on the current input box.
- Enter only commits the current field and does not start the export.
- Tab only moves GUI focus.
- Esc closes the GUI.
- E, WASD, the number hotbar keys, Q, F and unbound mod keys are all consumed.
- `keyReleased` does not leak into the game.
- After the GUI closes no modified global KeyMapping state remains.

## 11. Manual visual acceptance

Manual visual acceptance is performed by the project owner, and automation must not declare a visual pass on its own.

### 11.1 Create comparison

1. In the game, build a continuous structure containing at least andesite casings, brass casings, a fluid tank or a vault.
2. Save a target image or an on-site screenshot.
3. Export the same selection and import it into Blender.
4. Compare every edge, corner, center cell and texture direction.
5. Check that the connected textures did not fall back to a single-block repeat and did not sample across Sprites.

### 11.2 Grass block

1. Export a selection containing grass blocks of several biome tints.
2. Confirm in the Blender Outliner that there is only one `selection/grass_side_overlay`.
3. After hiding that object only the underlying dirt-grass edge remains; after restoring it the look matches the game.
4. Check that the Overlay was not baked, did not lose color and was not split into a large number of objects.

### 11.3 Input method and hotkeys

1. Type an English name containing `e` in the name box.
2. Type a Chinese export name with a Chinese input method.
3. Press E, WASD, 1 to 9, Q, F and installed mod hotkeys separately.
4. The GUI stays open throughout, and the player and inventory do not respond.
5. After closing with Esc, test the game hotkeys again and confirm they are restored immediately.

## 12. Release acceptance

The candidate version may be handed to manual visual acceptance only after the following mechanical checks are done:

- All related targeted tests pass.
- `clean test build` succeeds.
- `runServerSmoke` succeeds.
- The production JAR contains no Create or Catnip test doubles or testmod classes.
- `jdeps` shows no Create/Catnip bytecode dependency.
- The glTF internal validation passes.
- The OBJ/MTL structure check passes.
- The report can trace the Sprite redirects and the Overlay object.

The final visual pass conclusion is given only by the project owner in a real Create scene and in Blender.
