# MC glTF Exporter Design Specification

- Status: approved
- Date: 2026-08-10
- Target version: Minecraft 1.21.1
- Mod platform: NeoForge 21.1.244 and later compatible 21.1.x
- Java: 21
- Mod ID: `mcgltf`
- Display name: `MC glTF Exporter`
- Java package: `com.onecuber.mcgltf`
- Runtime environment: physical client only

## 1. Goals

Build a NeoForge 1.21.1 client mod that lets players export the map region currently loaded on the client as a glTF 2.0 scene, through an in-game two-point selection command.

The exported content includes:

- Ordinary block models of vanilla and other mods
- Fluids of vanilla and other mods
- Block-entity rendered content of vanilla and other mods
- The current static pose of ordinary entities inside the selection
- The textures actually used by blocks, fluids, block entities and entities
- Standalone glTF materials and the matching standalone Minecraft material description JSON
- A structured report of missing chunks, compatibility degradations and failed objects

Editing and rendering in Blender is the primary usage scenario of the output.

## 2. Confirmed product constraints

### 2.1 Selection method

A WorldEdit-style two-point selection is used:

```text
/mcgltf pos1
/mcgltf pos2
/mcgltf export <name>
```

`pos1` and `pos2` take the block coordinates the player stands on when running the command. The two points together define an inclusive closed integer block interval.

### 2.2 Runtime side

The mod loads only on the physical client. The export reads the mods, resource packs, baked models, entity renderers, texture atlases and dynamic textures actually enabled on the client.

The server does not need this mod. The client can only export data the server has already sent and that is still loaded on the client.

### 2.3 Entity scope

Exported:

- Ordinary blocks
- Fluids
- Block entities
- Ordinary entities
- Mobs, vehicles, armor stands, dropped items and modded entities

Excluded:

- Players
- Particles
- Weather
- Sky and clouds
- Entity shadows
- Entity name tags
- Debug bounding boxes

Entities are output as a static pose at capture time, and no Minecraft animation controller or timeline is output.

### 2.4 Unloaded chunks

Unloaded chunks inside the selection are skipped directly. The job continues, and the coordinates and count of the missing chunks are recorded in `report.json`.

The interface between a missing chunk and a loaded chunk is treated as air, keeping the boundary surfaces of the loaded region.

### 2.5 Lighting

A clean material mode is used:

- Keep the base texture
- Keep alpha
- Keep biome tinting
- Keep entity colors
- Keep emissive semantics
- Do not bake sky light
- Do not bake block light
- Do not bake Minecraft ambient occlusion

### 2.6 Output location

Default output directory:

```text
.minecraft/mcgltf-exports/<name>/
```

When a directory with the same name exists, an incrementing suffix is generated, such as `castle-2`, `castle-3`. An official export directory is never overwritten.

### 2.7 Objects that cannot be captured

When geometry cannot be obtained through the standard Minecraft/NeoForge vertex channels, a translucent magenta bounding-box placeholder is generated. The placeholder node records the registry ID, coordinates, renderer class name and failure reason.

## 3. Technical approach conclusion

A render-aware hybrid export pipeline is adopted:

1. Ordinary blocks are extracted from the final `BakedModel/BakedQuad`.
2. Fluids are extracted through `BlockRenderDispatcher#renderLiquid` and a capturing `VertexConsumer`.
3. Block entities call the actual `BlockEntityRenderer` and capture its `MultiBufferSource` output.
4. Ordinary entities call the actual `EntityRenderer` and capture its `MultiBufferSource` output.
5. Direct GPU instancing, custom OpenGL or uninterpretable shader paths degrade to a material substitute or a placeholder.

Parsing the original model JSON is not the main path. NeoForge custom model loaders, OBJ, Composite and dynamic models all eventually converge into a `BakedModel`, and reading the final baked result gives higher compatibility.

Full-frame GPU capture is not used. Full-frame capture can hardly recover object identity, Sprite provenance, RenderType semantics and standalone materials stably.

## 4. Top-level modules

```text
command
   │
   ▼
job ──────► world
   │           │
   ▼           ▼
capture ──► scene ◄── material / texture
               │
               ▼
             gltf
               │
               ├─ report
               └─ output
```

### 4.1 `command`

Responsible for client commands and the selection state:

```text
/mcgltf pos1
/mcgltf pos2
/mcgltf export <name>
/mcgltf export <name> confirm
/mcgltf status
/mcgltf cancel
```

The selection is bound to the current dimension. Changing dimension, leaving the world or disconnecting from the server clears the selection.

### 4.2 `job`

`ExportJobManager` maintains a globally unique export job:

```text
IDLE
→ PLANNING
→ CAPTURING
→ WRITING
→ COMPLETED | CANCELLED | FAILED
```

Only one active job is allowed on a client at a time. A job provides progress, cancellation, exception isolation and chat feedback.

### 4.3 `world`

Responsible for:

- Normalizing the two selection points
- Computing the integer closed interval
- Enumerating the chunks and chunk sections involved
- Marking loaded and unloaded chunks
- Providing selection boundary checks
- Providing the selection-clipped view used by fluid rendering

This module describes the export range and does not parse models.

### 4.4 `capture`

Contains:

- `BlockModelExtractor`
- `FluidGeometryCapture`
- `BlockEntityCapture`
- `EntityCapture`
- `CapturingMultiBufferSource`
- `CapturingVertexConsumer`
- `PlaceholderFactory`
- `RenderTypeInspector`

Minecraft client rendering APIs and Access Transformer access are concentrated in this module.

### 4.5 `scene`

A pure-Java intermediate model:

```text
ExportScene
├─ SceneMetadata
├─ ChunkMesh
├─ CapturedObject
├─ MeshPrimitive
├─ VertexData
├─ MaterialKey
├─ TextureKey
└─ Diagnostic
```

`scene` does not depend on `BlockState`, `Entity`, `BakedQuad` or other Minecraft types.

### 4.6 `texture`

Responsible for:

- Block Sprite cropping
- Reading entity resource textures
- Copying dynamic texture pixels
- Localizing atlas UV coordinates
- Registering texture sources and output paths
- Static previews of animated textures and saving of the source files

### 4.7 `material`

Responsible for mapping RenderType, texture, transparency, culling, emission and sampling semantics to glTF PBR materials and standalone material JSON.

### 4.8 `gltf`

Contains:

- `BinaryBufferWriter`
- `GltfDocumentBuilder`
- `GltfExporter`

Uses streaming `.bin` writing and final JSON assembly, and never builds a vertex object tree of the whole world in memory.

### 4.9 `report`

Responsible for export statistics, missing chunks, compatibility degradations, failed objects, elapsed time and job status.

## 5. Job data flow

```text
export command
→ argument and selection validation
→ chunk load state planning
→ create temporary directory
→ time-sliced capture of entities and chunks
→ seal chunk batches
→ background write of BIN/PNG/JSON
→ structure validation
→ atomic publish of the official directory
```

### 5.1 Client thread responsibilities

The client thread performs:

- Reading `ClientLevel`
- Calling BakedModel
- Calling the fluid, BER and entity renderers
- Reading texture atlases and dynamic textures
- Converting capture results into pure-Java batches

### 5.2 Background thread responsibilities

The single background thread performs:

- PNG encoding
- Material JSON writing
- Streaming `.bin` writing
- `.gltf` JSON assembly
- `report.json` writing
- Final directory publishing

### 5.3 Queue and budget

Default configuration:

```text
captureBudgetMs = 6
writerQueueCapacity = 2
softBlockLimit = 4_194_304
```

Each client tick spends at most about 6 ms of capture budget. A single indivisible mod renderer call may exceed the budget, and its time must go into the report.

When the soft volume limit is exceeded, a plain `export` only shows a warning and the confirmation command; the job starts only with `export <name> confirm`.

The background queue capacity is two sealed batches. When the queue is full, capture pauses to prevent unbounded memory growth.

### 5.4 Capture order

1. First capture the ordinary entities inside the selection.
2. Chunks are stably sorted by X, Z.
3. Each chunk is processed in 16-block sections along the Y direction.
4. At each position, ordinary blocks and fluids are processed in turn.
5. Block entities inside a section produce independent object nodes.
6. A sealed section is submitted to the background thread.

The stable ordering is used to produce an output structure that can be compared and tested.

### 5.5 Consistency

The export uses time-sliced rolling snapshots. Each object reads its state once when it is processed. If the world changes during a large export, it is not guaranteed that all chunks come from the same game tick.

The report records:

- `captureStartGameTime`
- `captureEndGameTime`
- `snapshotMode: rolling`

## 6. Ordinary block capture

For each block position the `BlockState`, `BakedModel` and `ModelData` are obtained.

The base ModelData comes from the matching block entity; then the model's `getModelData(level, pos, state, baseData)` is called, supporting connected textures and mod dynamic data.

Render layers are enumerated through `getRenderTypes(state, random, modelData)`. For each direction, using the block-state position seed, call:

```text
getQuads(state, direction, random, modelData, renderType)
```

### 6.1 Face culling

- Inside the selection, the vanilla `Block.shouldRenderFace` is used
- Outside the selection is treated as air
- Unloaded neighboring chunks are treated as air
- Non-occludable Quads with `direction == null` are always kept
- Air and `RenderShape.INVISIBLE` are skipped
- `ENTITYBLOCK_ANIMATED` is handled by the block-entity capturer for dynamic content

### 6.2 Quad conversion

For each Quad:

1. Apply the position offset of the block state.
2. Convert atlas UVs to Sprite-local `[0,1]` UVs.
3. Read the BlockColors biome tint.
4. Multiply the original vertex color by the Tint.
5. Ignore AO, sky light and block light.
6. Generate two triangles from 4 vertices and 6 indices.
7. Apply the coordinate transform, normal transform and winding correction.

No whole-chunk vertex hash merge is performed. The 4 vertices are reused within each Quad, to avoid wrongly merging vertices with different normals and UVs.

## 7. Fluid capture

Output goes through `BlockRenderDispatcher#renderLiquid` into a capturing `VertexConsumer`.

A selection-clipped `BlockAndTintGetter` is passed by default: outside the selection it returns air and empty fluid, so the water surface and fluid sides are closed at the selection boundary.

If a mod fluid wrongly force-casts the interface to `ClientLevel`:

1. Record a compatibility warning.
2. Retry with the real ClientLevel.
3. Mark in the report that the boundary may not be closed.

Fluid texture identification order:

1. The Still, Flowing and Overlay Sprites provided by `IClientFluidTypeExtensions`
2. Match candidate Sprites by the UV range of one primitive
3. Query the UV region index of the complete block atlas
4. Degrade to a white texture and vertex colors

## 8. Block entity capture

Obtain the actual `BlockEntityRenderer` and call it with a local-origin PoseStack and a capturing MultiBufferSource.

The static BakedModel of an ordinary block and the dynamic BER geometry are both kept. A block entity is output as a standalone glTF node, which stores:

- The block entity registry ID
- World coordinates
- Local coordinates
- The renderer class name
- Degradation information

## 9. Ordinary entity capture

Query the entities inside the selection AABB:

- Exclude Player
- Exclude removed entities
- Each entity uses its own capture context
- Keep armor, held items, additional render layers and leashes
- Ignore name tags, shadows, fire and debug wireframes
- Glint degrades to a standalone translucent emissive layer
- The current model or skeleton pose is baked into static vertices

An entity node stores:

- The entity registry ID
- UUID
- World coordinates
- Local coordinates
- The renderer class name
- Degradation information

## 10. Vertices and topology

Intermediate vertex format:

```text
POSITION : float32 × 3
NORMAL   : float32 × 3
TEXCOORD : float32 × 2
COLOR_0  : uint8 normalized × 4
INDEX    : uint32
```

Topology mapping:

| Minecraft mode | glTF mode |
|---|---|
| QUADS | TRIANGLES |
| TRIANGLES | TRIANGLES |
| TRIANGLE_STRIP | TRIANGLE_STRIP |
| TRIANGLE_FAN | TRIANGLE_FAN |
| LINES | LINES |
| LINE_STRIP | LINE_STRIP |
| other modes | placeholder |

Each MeshPrimitive corresponds to exactly one MaterialKey. Ordinary blocks are merged by chunk and material; entities and block entities stay independent nodes.

## 11. Coordinate system

Minecraft coordinates are converted to glTF right-handed coordinates:

```text
(X, Y, Z) → (X, Y, -Z)
```

At the same time:

- The Z component of normals is negated
- Triangle winding is reversed
- The minimum point of the selection is the local origin
- The world origin and the complete selection coordinates are written into the root node `extras`

One block equals one meter. The Blender glTF importer is responsible for converting from glTF Y-Up to Blender Z-Up.

## 12. Textures

Textures are not repacked into an atlas. Output paths keep the resource identity:

```text
textures/<namespace>/<path>.png
```

The same resource location is output only once. Different resource locations stay independent files even if their pixels are identical.

Dynamic textures are output to:

```text
textures/generated/<first 16 chars of sha256>.png
```

When reading a dynamic texture fails:

- The geometry is kept
- A purple-black checkerboard texture is used
- It is written into the report

### 12.1 Animated textures

- The glTF material uses the first logical frame
- The original animated PNG is saved to `textures/source/<namespace>/<path>.png`
- The `.mcmeta` is saved next to the matching PNG as `<file name>.png.mcmeta`
- The material JSON records the frame size, sequence and durations
- The report marks `ANIMATED_TEXTURE_STATIC_PREVIEW`

## 13. Materials

MaterialKey consists of the following fields:

- TextureKey
- AlphaMode
- AlphaCutoff
- DoubleSided
- Emissive
- BlendSemantic
- SamplerMode

Biome and entity colors are expressed through `COLOR_0` and do not create color material variants.

Default PBR:

```text
metallicFactor = 0
roughnessFactor = 1
baseColorFactor = [1, 1, 1, 1]
```

Material semantics:

| Minecraft semantic | glTF |
|---|---|
| solid | OPAQUE |
| cutout / cutout_mipped | MASK, alphaCutoff 0.5 |
| translucent | BLEND |
| eyes / emissive | emissiveTexture / emissiveFactor |
| glint | BLEND + Emissive degradation layer |
| culling disabled | doubleSided |
| unknown custom RenderType | inferred from transparency, cull and texture state |

Sampling defaults to nearest-neighbor, keeping pixel edges.

### 13.1 Standalone material JSON

Each glTF material has a standalone description file, containing at least:

```json
{
  "schemaVersion": 1,
  "name": "create_block_brass_casing_opaque",
  "gltfMaterialIndex": 12,
  "sourceTexture": "create:block/brass_casing",
  "exportedTexture": "../textures/create/block/brass_casing.png",
  "renderType": "solid",
  "alphaMode": "OPAQUE",
  "alphaCutoff": null,
  "doubleSided": false,
  "emissive": false,
  "sampler": "nearest",
  "degradations": []
}
```

## 14. glTF scene structure

```text
MCGLTF_<name>
├─ Chunks
│  ├─ Chunk_0_0
│  └─ Chunk_0_1
├─ BlockEntities
├─ Entities
└─ Placeholders
```

The root node extras store:

- The Minecraft version
- The NeoForge version
- The current dimension
- The world coordinates of the selection
- The local origin
- The resource pack list
- The loaded mods and versions
- The capture start and end time
- The generator version

## 15. Output structure

```text
.minecraft/mcgltf-exports/<name>/
├─ <name>.gltf
├─ <name>.bin
├─ textures/
│  ├─ <namespace>/<path>.png
│  ├─ generated/<hash>.png
│  └─ source/
├─ materials/
│  └─ <material-name>.json
└─ report.json
```

Export names allow Chinese and regular Unicode, normalized to NFC. Rejected:

- `/` and `\`
- Control characters
- `.` and `..`
- Windows reserved names
- Trailing spaces or periods
- Names longer than 64 Unicode code points

## 16. Transactional output

A job first writes to:

```text
.minecraft/mcgltf-exports/.tmp-<UUID>/
```

After all files are closed successfully and pass the internal structure check, the directory is atomically renamed to the official directory.

On failure or cancellation only this job's temporary directory is cleaned. Existing official exports are never modified.

## 17. Error policy

### 17.1 Fatal errors

The following errors terminate the job:

- Not in a world
- Incomplete selection
- Cross-dimension selection
- Output directory not writable
- Background writer failure
- Invalid glTF index relations
- Out of memory
- Out of disk space
- Leaving the world, disconnecting or a resource pack reload

### 17.2 Degradable errors

The following errors let the job continue:

- An exception in a single model or renderer
- A single texture that cannot be read
- A custom RenderType that cannot be interpreted
- A GPU-instanced object with no vertex output
- A dynamic texture read failure
- Unsupported vertex topology

Degradation order:

1. Geometry valid, texture failed: purple-black checkerboard texture.
2. Geometry valid, material unknown: generic PBR material.
3. Geometry invalid: translucent magenta bounding box.
4. The node extras and the report both record the diagnostic information.

## 18. Report

`report.json` contains at least:

```json
{
  "schemaVersion": 1,
  "status": "completed_with_warnings",
  "snapshotMode": "rolling",
  "selection": {
    "dimension": "minecraft:overworld",
    "min": [0, 64, 0],
    "max": [31, 95, 31],
    "localOrigin": [0, 64, 0],
    "volume": 32768
  },
  "captureStartGameTime": 123400,
  "captureEndGameTime": 123457,
  "counts": {
    "scannedPositions": 32768,
    "renderedBlocks": 12000,
    "renderedFluids": 300,
    "blockEntities": 8,
    "entities": 4,
    "materials": 24,
    "textures": 21,
    "triangles": 92000,
    "placeholders": 1
  },
  "missingChunks": [[2, 0]],
  "warnings": [],
  "failures": [],
  "timingsMs": {
    "planning": 4,
    "capture": 1200,
    "writing": 310,
    "total": 1514
  }
}
```

`scannedPositions` counts the block positions actually visited inside loaded chunks; `renderedBlocks` and `renderedFluids` only count objects that produced valid geometry. Each diagnostic contains a stable error code, object ID, coordinates, renderer class name, exception type and a readable message.

## 19. Test strategy

All production behavior uses test-driven development.

### 19.1 Pure-Java unit tests

Cover:

- Selection normalization
- Unicode path safety
- Coordinate and normal transforms
- Triangle winding
- Quad triangulation
- UV localization
- Vertex color normalization
- MaterialKey deduplication
- RenderType semantic mapping
- BufferView four-byte alignment
- Accessor min/max
- Index bounds
- State machine and cancellation
- Report serialization

### 19.2 Capture adapter tests

Use controllable fake models and fake renderers to verify:

- Block seed stability
- Internal face culling
- Selection boundary closing
- Tint Index
- ModelData passing
- Per-material split for multiple RenderTypes
- Last-vertex submission of VertexConsumer
- All supported topologies
- Exception and zero-vertex degradation

### 19.3 Development client integration scene

The test scene contains:

- Stone, grass block, leaves, glass
- Water, lava
- Chest, sign, banner
- Cow, armor stand, dropped item, boat
- A custom dynamic BakedModel
- A custom ModelData model
- A custom transparent RenderType
- A custom fluid
- A custom BER
- A custom entity renderer
- A stand-in for a deliberately uncapturable GPU path

### 19.4 glTF validation

The end-to-end result must satisfy:

- All URIs are relative paths
- All referenced files exist
- BufferViews are four-byte aligned
- Accessor ranges are correct
- Indices are valid
- Vertex attributes contain no NaN or Infinity
- Normal lengths are within tolerance
- Primitive material references are valid
- The Khronos glTF Validator reports zero errors

### 19.5 Blender acceptance

- No manual texture relinking needed
- A clear scene hierarchy
- Correct biome colors
- Correct OPAQUE, MASK and BLEND
- Entities keep their static pose
- One block per meter
- The world coordinates can be recovered from extras
- Standalone textures can be replaced

## 20. Performance acceptance

- Default capture budget of about 6 ms per tick
- A single indivisible call that times out is recorded
- The write queue never exceeds two batches
- The vertex memory of an already-written chunk section can be released
- Whole-scene vertices are not stored
- Memory complexity is about the sum of the current section, two write batches and the global index
- After cancellation no new capture batches are produced
- Cancellation and failure produce no official result directory

## 21. Functional acceptance

- [ ] pos1 and pos2 are saved correctly
- [ ] A cross-dimension selection is rejected
- [ ] Unloaded chunks are skipped and reported
- [ ] Vanilla and modded BakedModels can be exported
- [ ] Fluid surfaces and selection boundaries are correct
- [ ] Block entities can be exported
- [ ] Ordinary entities can be exported
- [ ] Players are excluded
- [ ] Textures are output independently
- [ ] Material JSON is output independently
- [ ] Uncapturable objects generate placeholders
- [ ] Blender can import directly
- [ ] The glTF Validator reports zero errors
- [ ] Failures do not pollute the official output

## 22. Implementation milestones

1. NeoForge project and the pure-Java glTF core.
2. Commands, selection and the job state machine.
3. The BakedModel block and Sprite texture loop.
4. Fluids, biome colors and transparent materials.
5. Block entity and ordinary entity capture.
6. Placeholder degradation, report, cancellation and transactional output.
7. Integration tests, Khronos Validator and Blender acceptance.

Every milestone must produce a state that can be built, tested and committed.

## 23. Known compatibility boundaries

| Object | Expectation |
|---|---|
| Vanilla and modded JSON blocks | High |
| NeoForge custom BakedModel | High |
| OBJ, Composite and ModelData models | High |
| Biome tinting, random variants, connected textures | High |
| Standard and modded fluids | High |
| Standard BER and EntityRenderer | Fairly high |
| Skeletal entities output through MultiBufferSource | Fairly high, static pose |
| Flywheel or other GPU-instanced objects | Placeholder or adapter extension |
| Custom shader effects | Base material degradation or placeholder |

## 24. References

- [NeoForge 1.21.1 Getting Started](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge 1.21.1 BakedModel](https://docs.neoforged.net/docs/1.21.1/resources/client/models/bakedmodel/)
- [NeoForge 1.21.1 Custom Model Loaders](https://docs.neoforged.net/docs/1.21.1/resources/client/models/modelloaders/)
- [NeoForge 1.21.1 Block Entity Renderer](https://docs.neoforged.net/docs/1.21.1/blockentities/ber/)
- [NeoForge 1.21.1 Client Sides](https://docs.neoforged.net/docs/1.21.1/concepts/sides/)
- [NeoForge 1.21.1 Access Transformers](https://docs.neoforged.net/docs/1.21.1/advanced/accesstransformers/)
- [NeoForge 1.21.1 RegisterClientCommandsEvent](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/event/RegisterClientCommandsEvent.java)
- [NeoForge 1.21.1 BlockRenderDispatcher Patch](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/client/renderer/block/BlockRenderDispatcher.java.patch)
- [NeoForge 1.21.1 IClientFluidTypeExtensions](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/extensions/common/IClientFluidTypeExtensions.java)
- [Minecraft 1.21.1 EntityRenderDispatcher mappings](https://mappings.dev/1.21.1/net/minecraft/client/renderer/entity/EntityRenderDispatcher.html)
- [Minecraft 1.21.1 VertexConsumer mappings](https://mappings.dev/1.21.1/com/mojang/blaze3d/vertex/VertexConsumer.html)
- [Khronos glTF 2.0 Specification](https://github.com/KhronosGroup/glTF/tree/main/specification/2.0)
- [Khronos glTF Validator](https://github.com/KhronosGroup/glTF-Validator)
- [MiEx Minecraft World Exporter](https://github.com/BramStoutProductions/MiEx)
- [Flywheel](https://github.com/Engine-Room/Flywheel)
