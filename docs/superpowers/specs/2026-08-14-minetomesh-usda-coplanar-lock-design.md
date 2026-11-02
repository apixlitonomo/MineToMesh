# MineToMesh USDA, Coplanar Layering and Persistent Selection Design

## 1. Background

MineToMesh currently outputs both glTF 2.0 and OBJ. glTF suits runtime interchange, but its standard topology supports only triangles, points and lines; OBJ can keep quads but lacks reliable scene hierarchy, instancing and modern material expression. CG software such as Blender also renders the exactly coplanar multi-layer geometry of original Minecraft models as black spots or flicker, for example zero-thickness powered rails and the grass block base color layer with its tint Overlay.

This change adds text-based OpenUSD (USDA) as a CG production format, immediately removes the current OBJ/MTL output, and corrects exactly coincident quads in ordinary block models before the formats split. At the same time, the wand GUI gains a client-side persistent "Lock Selection" so that the selection box can still be restored when the wand is not in hand, after reconnecting, or after a client restart.

## 2. Confirmed decisions

- Every export produces glTF, BIN, USDA, textures, material sidecars and a report.
- OBJ/MTL is removed immediately, with no hidden switch or transition version.
- Historical release records are kept, while the current README, test matrix and implementation documents change to glTF/USDA.
- USDA uses `.usda` text files and external PNGs, and no USDC or USDZ is generated.
- USDA keeps the original Minecraft quads; glTF continues to be triangulated as the specification requires.
- All exactly coincident faces are kept, and later layers are offset slightly along their own normals.
- Coincidence detection applies only to ordinary block `BakedQuad`s and does not change the output of fluid, entity and block-entity Renderers.
- A new client-side "Lock Selection" option is added, independent from the existing hand preview.
- Each singleplayer world or multiplayer server saves only one last locked selection; a new lock overwrites the old record.
- The locked selection persists across restarts and is isolated per world or server.

## 3. Non-goals

- No merging of quads across adjacent blocks and no construction of large N-gons.
- No detection of arbitrary coplanar polygons that intersect only over part of their area.
- No tolerance matching for approximately coplanar geometry.
- No change to the geometry topology of dynamic Renderers, fluids or entities.
- No integration of the native OpenUSD C++ runtime library.
- No generation of USDC, USDZ, Alembic, COLLADA or PLY.
- No Java API compatibility layer kept for old OBJ callers.
- No change to the standard triangle topology of glTF or to the Khronos validation requirement.

## 4. Overall architecture

Three independent chains:

```text
ordinary block BakedQuad
  → exact coincidence detection and layered offset
  → PrimitiveAccumulator
  → immutable ChunkBatch
      ├─ StreamingGltfSession
      └─ StreamingUsdaSession

current selection in the wand GUI
  → LockedSelectionStore
  → persisted per world/server
  → SelectionOverlayRenderer
```

`StreamingSceneSession` manages the glTF and USDA Writers together. If either Writer fails to open, append or finish, the whole `OutputTransaction` rolls back and no half scene is published.

The output directory is fixed as:

```text
<export name>/
├─ <export name>.gltf
├─ <export name>.bin
├─ <export name>.usda
├─ textures/
├─ materials/
└─ report.json
```

## 5. Exactly coincident quad handling

### 5.1 Where and what

Processing happens after `BlockModelExtractor` has collected all `PendingStream`s of a single block and before they are routed to an ordinary Section or the global grass Overlay. This lets the grass block base color layer and the Overlay take part in the same round of detection before being split into different Accumulators.

Detection handles only four-vertex block faces. Each block is grouped separately, to avoid mistaking shared boundaries of neighboring blocks for the same coincident group.

### 5.2 Exact geometry fingerprint

Each quad produces a `FaceGeometryKey`:

1. Read the positions of the four vertices.
2. Normalize `-0.0F` to `0.0F`.
3. Represent each coordinate with `Float.floatToIntBits()`.
4. Sort the four XYZ tuples in lexicographic order.
5. The sorted result forms an immutable Key.

The Key does not include winding, normals, UVs, material, color, transparency mode or Overlay routing. Therefore same-direction cover layers, reversed zero-thickness double faces and exactly coplanar quads with different materials all fall into the same group.

The first version uses no tolerance. Any representable difference in a coordinate means no match.

### 5.3 Offset rule

Faces in the same group keep their original capture order:

```text
layer 0: 0
layer 1: normal × 1/1024
layer 2: normal × 2/1024
layer N: normal × N/1024
```

For each layer the normal is preferably the normalized average of the four vertex normals. If the average is invalid, the geometric normal computed from the cross product of the vertex positions is used and kept in the same direction as the available vertex normals. If both normals are invalid, that layer is not moved and `COPLANAR_FACE_NORMAL_INVALID` is recorded.

The offset only replaces `Vertex.position`, keeping normals, UVs, Color, MaterialKey, PrimitiveMode, winding and routing.

### 5.4 Statistics and report

The `report.json` schema is upgraded with:

```json
"geometryAdjustments": {
  "coplanarGroups": 18,
  "offsetFaces": 21,
  "maxLayers": 3,
  "byBlock": {
    "minecraft:grass_block": 12,
    "minecraft:powered_rail": 4
  }
}
```

Ordinary hits only accumulate statistics and do not generate a Diagnostic per face. A Diagnostic is written only for abnormal paths such as an offset normal that cannot be computed, so that large grass areas do not bloat the report.

## 6. USDA stage contract

### 6.1 Stage metadata

The USDA header is:

```usda
#usda 1.0
(
    defaultPrim = "MineToMesh"
    metersPerUnit = 1
    upAxis = "Y"
)
```

Y-Up is used so that coordinates after Blender import keep satisfying:

- Minecraft `+X` maps to Blender `+X`.
- Minecraft `+Y` maps to Blender `+Z`.
- Minecraft `+Z` maps to Blender `-Y`.

All polygon meshes write:

```usda
uniform token subdivisionScheme = "none"
```

so that DCC tools do not interpret Minecraft quads as Catmull-Clark subdivision surfaces.

### 6.2 Scene hierarchy

The stage uses the following paths:

```text
/MineToMesh
├─ Chunks
├─ BlockEntities
├─ Entities
├─ Placeholders
├─ Overlays
└─ Materials
```

`/MineToMesh` stores the Minecraft version, NeoForge version, exporter version, dimension, selection, origin, resource packs, mod list, snapshot mode and export options as custom attributes.

Ordinary capture nodes stay independent Xforms. The surface primitives in a node are merged into one or a few Meshes, and a `UsdGeomSubset` binds materials by complete `MaterialKey`. Because USD's `doubleSided` belongs to the Gprim, meshes are bucketed at least by the double-sided attribute, and different double-sided semantics cannot be forced into the same Mesh.

Block entities continue to be batched globally by complete `MaterialKey`; ordinary entities stay independent; the grass side Overlay is merged into a single selection-level object.

### 6.3 Topology mapping

| Internal mode | USDA representation |
|---|---|
| `QUADS` | every entry of `faceVertexCounts` is 4 |
| `TRIANGLES` | every entry of `faceVertexCounts` is 3 |
| `TRIANGLE_FAN` | expanded into triangles preserving winding |
| `TRIANGLE_STRIP` | expanded into triangles preserving parity winding |
| `LINES` | linear, non-periodic `BasisCurves` |
| `LINE_STRIP` | linear, non-periodic `BasisCurves` |

The current capture layer has no generic N-gon PrimitiveMode; the USDA data model allows future direct writing of arbitrary `faceVertexCounts`, and this change does not rebuild N-gons across blocks.

### 6.4 Vertex attributes

Mesh output:

- `points`
- `normals`
- `faceVertexCounts`
- `faceVertexIndices`
- `primvars:st`
- `primvars:minetomeshTint`

UVs, normals and Tint use `faceVarying`, keeping face-corner seams. USD and glTF use different vertical texture conventions, so USDA writes `st` with:

```text
st.x = uv.x
st.y = 1.0 - uv.y
```

### 6.5 Material network

Each complete `MaterialKey` corresponds to one stably named `UsdShadeMaterial`. The name consists of a readable prefix and the hash of the complete key, so that materials with the same texture name but different transparency, emission or sampling semantics are not merged by mistake.

Materials use standard nodes:

```text
UsdPreviewSurface
├─ UsdUVTexture
├─ UsdPrimvarReader_float2    reads st
└─ UsdPrimvarReader_float4    reads minetomeshTint
```

`UsdUVTexture.inputs:scale` connects to the Tint Primvar, so texture sampling is multiplied by the biome color. The texture RGB connects to `diffuseColor`; alpha connects to `opacity`. MASK materials write `opacityThreshold`, BLEND materials keep continuous transparency; emissive materials also connect `emissiveColor`.

The Mesh's `doubleSided` is written according to the bucketed material semantics.

UsdPreviewSurface has no cross-DCC unified nearest-neighbor filtering field. USDA writes a custom `minetomesh:samplerMode` and keeps storing the exact sampling semantics in the `materials/` sidecar. Blender 5.2 manual acceptance checks how nearest-neighbor is restored; if the native importer does not read this information, the README explicitly asks to set the Image Texture interpolation to Closest. No non-standard USD Shader is faked in this change.

### 6.6 Streaming writes

`StreamingUsdaSession` does not hold the complete world in memory. It maintains controlled temporary fragments for categories, the global Overlay and material-batched block entities, for example:

```text
.<name>-chunks.usdapart
.<name>-block-entities-<material>.usdapart
.<name>-overlay.usdapart
```

Each `ChunkBatch` only appends text fragments, array elements and counts. `finish()` assembles the stage header, root node, categories, materials and closing brackets, and validates array lengths, face counts, index ranges and reference paths.

The completion, cancellation, construction-failure and mid-way exception paths must all close the Writer and delete the `.usdapart` files.

## 7. OBJ removal

Delete the current production code:

- `StreamingObjSession`
- `ObjTopologyConverter`
- `ObjNames`
- The OBJ lifecycle and statistics in `StreamingSceneSession`
- The current OBJ contract in the current README, test matrix, output transaction assertions and documents

Delete the corresponding current tests, and cover quads, materials, batching, Overlay, cancellation and temporary file cleanup with equivalent USDA tests. The old-version `docs/releases/` keep their original text, because they describe what was actually released at the time.

The `formats` in the root Extras changes to `["gltf", "usda"]`, `sourceTopologyPreservedInObj` is removed, and `sourceTopologyPreservedInUsda=true` is added.

## 8. Persistent locked selection

### 8.1 Data ownership

The existing `ExportWandSelection.overlayEnabled` continues to control the held-wand preview. The locked selection is purely client-side state: it is not added to the wand DataComponent, adds no network payload and does not change the server authorization flow.

A new `LockedSelectionStore` saves the last locked complete selection of each world or server.

### 8.2 Config format

Config path:

```text
.minecraft/config/minetomesh/locked-selections.json
```

Format:

```json
{
  "schemaVersion": 1,
  "profiles": {
    "<SHA-256 context key>": {
      "dimension": "minecraft:overworld",
      "pos1": [0, 64, 0],
      "pos2": [32, 96, 32]
    }
  }
}
```

The raw context is:

- Singleplayer: the normalized save root path.
- Multiplayer: the normalized server address and port.

The file only stores the SHA-256 and never stores the path or server address in plain text. Each profile has only one record, the dimension is part of the record, and locking in another dimension overwrites the original record.

### 8.3 Atomic write and corruption recovery

The write flow is a temporary file in the same directory, flush, then an atomic move replace. When the filesystem does not support atomic moves it degrades to a controlled replace.

The in-memory state is updated only after the disk write succeeds. When the write fails the old file and the old in-memory record are kept and the failure is shown in the GUI status bar.

When config parsing fails, the original file is quarantined as a timestamped `.corrupt` file, an empty Store is loaded and a log entry is written; the hand preview keeps working.

### 8.4 GUI behavior

The existing bottom row becomes three equal-width toggles:

```text
Preview: On | Lock: Off | Players: Off
```

Lock button rules:

- Current complete selection equals the current profile's locked record: clicking unlocks.
- Current complete selection differs from the locked record: clicking overwrites it with the current selection.
- Current selection incomplete: the operation is rejected and a status hint is shown.
- The lock state is based on "whether the current wand selection equals the persisted record".

### 8.5 Render resolution

`SelectionOverlayRenderer` resolves both:

1. The hand preview of the wand currently in the main hand or off hand.
2. The persisted lock record of the current world or server profile.

When the two snapshots are identical it draws only once; when they differ both are shown. A lock record is hidden when its dimension does not match the current one and restored after returning to the original dimension. When the current world profile cannot be established, only the persisted record is disabled and the hand preview is unaffected.

## 9. Error handling and transaction semantics

- If either the glTF or the USDA Writer fails, the whole export transaction fails and the temporary directory is cleaned.
- USDA array counts, topology and references run internal validation before publishing.
- glTF continues to run the existing internal validation and Khronos Validator acceptance.
- An abnormal coincident-face normal only affects that layer's offset and writes a Diagnostic, without interrupting the capture of the whole block.
- Persistent config read/write failures do not affect world rendering, the wand network state or export jobs.
- When the profile cannot be resolved, the dimension does not match or the record is incomplete, the lock box is not rendered.

## 10. Test strategy

All implementation follows TDD: add failing tests that reproduce the missing behavior first, then write the minimal implementation.

### 10.1 Coplanar layering automated tests

- Same-direction, reversed, rotated-winding, reverse-ordered and different-material exactly coincident quads hit the same group.
- `-0.0F` and `0.0F` are equivalent.
- The first layer does not move, and later layers increase by `1/1024`.
- Each layer moves along its own normal.
- Faces that are close but not exactly identical do not move.
- Statistics are correct for three layers and more.
- An invalid average normal uses the geometric normal; a fully degenerate case records a Diagnostic.
- The ordinary block integration path runs layering before routing the Overlay.

### 10.2 USDA automated tests

- A quad writes one four-sided face, while the same source data in glTF writes two triangles.
- Triangle, Fan, Strip, Lines and LineStrip map correctly.
- `subdivisionScheme="none"`, Y-Up, units, hierarchy and root metadata are correct.
- UVs apply `1-v`, and normals and Tint use `faceVarying`.
- PreviewSurface, texture, Tint, alpha threshold, emission and double-sided semantics are correct.
- Multi-material nodes use valid GeomSubsets.
- Block-entity global material batching and the global Overlay merge are correct.
- Publishing is refused for illegal array counts or indices.
- No `.usdapart` remains after completion, cancellation and exceptions.
- No `.obj` or `.mtl` exists in the output directory.

### 10.3 Persistent selection automated tests

- A singleplayer save, different servers and the same server are each isolated.
- Normalized addresses and paths produce stable hashes.
- Writing, reloading, overwriting and unlocking are correct.
- It is restored after a restart, hidden when the dimension does not match, and restored on return.
- A new selection overwrites the old record.
- A corrupted config is quarantined without crashing.
- The in-memory lock state is not updated when the write fails.
- Hand and lock snapshots are deduplicated when identical and both kept when different.
- An incomplete selection cannot be locked.

### 10.4 Regression tests

- The full JUnit suite.
- `compileJava`, `compileTestJava` and resource checks.
- A dedicated-server startup smoke test, ensuring the client persistent Store and the USD Writer do not pollute server class loading.
- README and manual matrix policy tests updated to glTF/USDA.

## 11. Blender 5.2 manual acceptance

- After USDA import, vanilla blocks keep their quads.
- Powered rails show no black spots, flicker or exactly coplanar layers.
- Both the grass block base color layer and the tint Overlay are kept.
- Crops show no visible change in appearance.
- Minecraft and Blender axes, origin, scale, winding and normals are correct.
- PNG, biome tint, cutout, transparency and emissive materials work.
- Dynamic block entities such as Create belts have correct hierarchy and material batching.
- The Outliner contains the fixed category hierarchy, and the grass side Overlay is a single selection-level object.
- The locked selection is restored across client restarts and reconnects.
- Different saves or servers never show other profiles' lock boxes.
- Dimension switching hides it and it is restored on return.
- glTF keeps passing the Khronos Validator, and the USDA imports into Blender 5.2 without errors.

## 12. Completion criteria

It is considered complete only when all of the following are met:

1. All automated tests pass.
2. The build and the dedicated-server smoke test pass.
3. The output contains only glTF/BIN/USDA and the shared auxiliary files, and no OBJ/MTL is generated any more.
4. The USDA keeps quads, hierarchy, coordinates and core material semantics in Blender 5.2.
5. The visual acceptance of the rail and grass coplanar cases is completed.
6. The persistent locked selection passes acceptance across restarts, across dimensions and for profile isolation.
7. Temporary fragments, failed transactions and corrupted configs are all cleaned up or quarantined as designed.
8. The README, test matrix and report schema agree with the actual behavior.
