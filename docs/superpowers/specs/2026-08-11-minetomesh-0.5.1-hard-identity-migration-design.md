# MineToMesh 0.5.1 Full Identity Hard Migration Design

Date: 2026-08-11  
Status: design confirmed, awaiting formal specification review  
Baseline branch: `main`  
Target version: `0.5.1`  
Minecraft: 1.21.1  
NeoForge: 21.1.244

## 1. Goals

MineToMesh 0.5.1 fully migrates the project's current internal `mcgltf` identity to `minetomesh`, covering the build artifact, the NeoForge Mod ID, the Java package, the resource namespace, network payloads, the command entry point, the test mod and the export directory.

This migration uses a hard-migration strategy and provides no legacy-namespace shim, Missing Mapping or command alias. When finished, production code, production resources, the current tests and the current release documentation use only the new MineToMesh identity.

## 2. Confirmed approach

A full hard migration is adopted, and the following compromises are not:

- Not only changing the in-game display name.
- Not only changing the JAR file name.
- Not keeping `mcgltf` as the runtime Mod ID.
- Not keeping the `com.onecuber.mcgltf` Java package.
- Not keeping a `/mcgltf` command alias.
- Not providing a missing-registry mapping from `mcgltf:*` to `minetomesh:*`.

## 3. 0.5.1 identity contract

| Identity item | 0.5.1 value |
|---|---|
| Version | `0.5.1` |
| NeoForge Mod ID | `minetomesh` |
| Display name | `MineToMesh` |
| Java root package | `com.nebysse.minetomesh` |
| Maven Group | `com.nebysse.minetomesh` |
| Build artifact | `MineToMesh-0.5.1.jar` |
| Resource namespace | `minetomesh:*` |
| Main command | `/minetomesh` |
| Client export directory | `.minecraft/minetomesh-exports/` |
| Test mod ID | `minetomesh_test` |
| Server smoke system property | `minetomesh.serverSmoke` |
| Server ready marker | `MINETOMESH_SERVER_READY` |
| Git tag | `v0.5.1` |
| GitHub Release title | `MineToMesh 0.5.1` |

## 4. Java package and class name migration

All version-controlled Java sources move to the following root paths:

```text
src/main/java/com/nebysse/minetomesh/
src/test/java/com/nebysse/minetomesh/
src/testmod/java/com/nebysse/minetomesh/
```

All `package com.onecuber.mcgltf...` declarations and the matching imports change to `com.nebysse.minetomesh...`.

The brand entry classes are renamed accordingly:

- `McGltf` → `MineToMesh`
- `McGltfClient` → `MineToMeshClient`
- `McGltfContent` → `MineToMeshContent`
- `McGltfMetadataTest` → `MineToMeshMetadataTest`
- `McGltfTestMod` → `MineToMeshTestMod`

Types that describe the glTF file format keep their original names, for example `GltfDocumentBuilder`, `StreamingGltfSession` and `InternalGltfValidator`. Here `Gltf` refers to the standard format and is not part of the old brand identity.

## 5. NeoForge and build identity

`gradle.properties` uses:

```properties
mod_id=minetomesh
mod_name=MineToMesh
mod_version=0.5.1
mod_group_id=com.nebysse.minetomesh
```

Gradle `archivesName` uses `mod_name`, ensuring the file name is strictly:

```text
MineToMesh-0.5.1.jar
```

The generated `META-INF/neoforge.mods.toml` must contain:

```toml
modId="minetomesh"
version="0.5.1"
displayName="MineToMesh"
```

The client and server dependency side stays `BOTH`. The main mod's test binding changes to `mods.minetomesh`, and the test mod registration name changes to `minetomesh_test`.

## 6. Registry and resource namespace

The resource directories are moved in full:

```text
assets/mcgltf/ → assets/minetomesh/
data/mcgltf/   → data/minetomesh/
```

All production code, JSON, language files, models, recipes, textures, data components, menus, items and network payloads use the `minetomesh` namespace.

Examples:

```text
mcgltf:export_wand → minetomesh:export_wand
mcgltf:wand_selection → minetomesh:wand_selection
```

Language keys are migrated in step, for example:

```text
item.mcgltf.export_wand → item.minetomesh.export_wand
itemGroup.mcgltf → itemGroup.minetomesh
```

The production JAR must not contain `assets/mcgltf/`, `data/mcgltf/` or any old workstation resources.

## 7. Network, command and runtime identifiers

The `ResourceLocation` namespace of all custom payloads changes to `minetomesh`. A 0.5.1 client is not guaranteed to be protocol-compatible with an old server; client and server must run the same version.

The command root node is hard-migrated from:

```text
/mcgltf
```

to:

```text
/minetomesh
```

No old command alias is registered.

The server smoke property changes from `mcgltf.serverSmoke` to `minetomesh.serverSmoke`, and the ready text keeps using `MINETOMESH_SERVER_READY`, which already follows the new brand.

Loggers, thread names, brand labels in diagnostics and test fixture descriptions use MineToMesh or `minetomesh`, and no new `mcgltf` runtime text is generated any more.

## 8. Export directory migration

The root directory for new exports is:

```text
.minecraft/minetomesh-exports/
```

0.5.1 does not move, merge or delete the user's existing:

```text
.minecraft/mcgltf-exports/
```

The old directory stays on disk for users to archive themselves. The new version only writes to `minetomesh-exports`.

The output format, same-name directory suffixes, transaction directories, glTF/OBJ file structure and report contents keep their existing behavior.

## 9. Breaking compatibility policy

This release explicitly chooses the C1 hard migration:

- No Missing Mapping is registered.
- No old Mod ID shim is kept.
- No old resource namespace shim is kept.
- No old payload IDs are kept.
- No old command alias is kept.
- No attempt is made to migrate the data components of old wands.

`mcgltf:*` items, components or registered objects in old worlds may become missing entries. The README and the GitHub Release Notes must prominently state: back up your world before upgrading, and 0.5.1 does not guarantee that old wands continue to exist.

This policy does not actively modify world files and does not actively delete old export files.

## 10. Documentation boundary

The current user documentation uniformly uses:

- `MineToMesh-0.5.1.jar`
- `/minetomesh`
- `.minecraft/minetomesh-exports/`
- `minetomesh:*`

Historical specifications, historical plans and migration notes may contain `mcgltf`, provided the context clearly refers to the old version or old identity. The automated residue scan must not simply forbid the old string across the whole repository, but should scan the production sources, production resources, the current tests and the generated JAR separately.

## 11. Test strategy

Implementation follows test-driven development, first establishing the failing 0.5.1 identity contract:

1. `MineToMesh.VERSION == "0.5.1"`.
2. The generated metadata contains `modId="minetomesh"` and `version="0.5.1"`.
3. The glTF generator is `MineToMesh 0.5.1`.
4. The README contains the new JAR, command, export directory and the breaking warning.
5. Java sources are located in `com.nebysse.minetomesh`.
6. The current production sources and production resources contain no old package declarations or old namespaces.
7. Only `/minetomesh` is registered as a command.
8. The output pipeline writes only to `minetomesh-exports`.
9. The test mod ID and the server smoke property use `minetomesh`.

Mechanical verification includes:

```powershell
.\gradlew.bat clean test build
.\gradlew.bat runServerSmoke
```

The production JAR audit must confirm:

- The file name is `MineToMesh-0.5.1.jar`.
- It contains `com/nebysse/minetomesh/`.
- It does not contain `com/onecuber/mcgltf/`.
- It contains `assets/minetomesh/` and `data/minetomesh/`.
- It does not contain `assets/mcgltf/`, `data/mcgltf/`, `mcgltf_test` or testmod classes.
- The metadata version, Mod ID, display name and side are correct.

## 12. Release process

Remote publishing is performed only after all mechanical verification passes:

1. Merge the migration branch into `main`.
2. Run the full build and server smoke test again on the merged `main`.
3. Push `main` to `minetomesh/main`.
4. Create the annotated tag `v0.5.1` and push it.
5. Create the GitHub Release `MineToMesh 0.5.1`.
6. Upload `MineToMesh-0.5.1.jar`.
7. The Release Notes list the identity migration, connected-texture and Overlay capabilities, and clearly state the hard-migration risk.
8. Compute and publish the SHA-256.
9. Verify that the remote `main`, the tag target and the Release asset hash match the local ones.

If GitHub authentication or the Release API is unavailable, stop at the last verified and recoverable step and report the specific blocker, without faking a completed release.

## 13. Acceptance criteria

0.5.1 may be declared released only after all of the following are satisfied:

- The version, Mod ID, Java package, Maven Group, JAR name and resource namespace match the identity contract.
- `/minetomesh` works and `/mcgltf` is not registered.
- New exports are written to `.minecraft/minetomesh-exports/`.
- `clean test build` is fully green.
- `runServerSmoke` loads MineToMesh 0.5.1 and prints `MINETOMESH_SERVER_READY`.
- The production JAR residue scan passes.
- `main` has been pushed.
- `v0.5.1` has been pushed and points to the release commit.
- The GitHub Release has been created and contains the JAR.
- The Release asset SHA-256 has been recorded.

The behavior of missing old-world entries, the new command interaction and the new export directory on a real client are accepted manually by the project owner; automated tests do not replace that manual conclusion.
