# terrain-eng — Core Terrain Rendering Engine & Bitmask Mesher

Owner: terrain-eng worker. Target: Minecraft 26.2 (Fabric Loader 0.20.1, Java 25), OpenGL and Vulkan (Blaze3D-level only; no backend-specific code).

All MC signatures were verified against the 26.2 client jar bytecode (`build/mc262-specs/terrain/*.txt` plus `javap` on `LevelRenderer`, `GameRenderer`, `DynamicUniformStorage`, `VertexSorting`, `Block`, `BlockStateBase`, the `feature.*FeatureRenderer`s, `FluidModel`, `BakedQuad`). Fabric API 0.161 mixins were scanned for overlapping targets; the only overlap is Fabric's `@Redirect` of `ModelBlockRenderer.tesselateBlock` inside `SectionCompiler.compile`. That redirect is why the compiler hooks here leave `compile` intact rather than replacing it.

## Mixins to register (relative to `com.ephemera.mixin.client`, `"client"` array)

| Mixin | Target | Purpose |
|---|---|---|
| `terrain.TerrainCompiledSectionMeshMixin` | `CompiledSectionMesh` | `SectionMeshRenderCache` duck: ordinal-indexed draws, allocation slots, Draw/UBO-record caches, sprite ids |
| `terrain.TerrainUberGpuBufferMixin` | `UberGpuBuffer` | `@WrapOperation` on `allocationMap.put/remove` mirrors allocations onto the mesh duck (vanilla map untouched) |
| `terrain.TerrainUberGpuBufferHeapAccessor` | `UberGpuBuffer$UberGpuBufferHeap` | `@Accessor gpuBuffer` |
| `terrain.TerrainSectionRenderDispatcherMixin` | `SectionRenderDispatcher` | tags the six uber buffers with slot and epoch at `<init>` RETURN |
| `terrain.TerrainSectionUberBuffersAccessor` | `SectionRenderDispatcher$SectionUberBuffers` (private record, string target) | `@Accessor vertexBuffer/indexBuffer` |
| `terrain.TerrainLevelRendererChunkBatchMixin` | `LevelRenderer` | `prepareChunkRenders` HEAD, cancellable → `ChunkRenderBatcher`; vanilla runs when it returns null |
| `terrain.TerrainSectionCompilerMixin` | `SectionCompiler` | `@Inject` at `betweenClosed`, `@ModifyExpressionValue` on `BlockState.getRenderShape()` (buried → `INVISIBLE`), RETURN publishes sprite ids |
| `terrain.TerrainSectionCompilerResultsMixin` | `SectionCompiler$Results` | `CompileResultsSprites` duck |
| `terrain.TerrainRenderSectionVertexSortingMixin` | `SectionRenderDispatcher$RenderSection` | `@WrapOperation` on `VertexSorting.byDistance(FFF)` in `createVertexSorting` → radix sorter |
| `terrain.TerrainTextureAtlasMixin` | `TextureAtlas` | block atlas `upload` RETURN assigns animated sprite ids; `clearTextureData` HEAD resets |
| `terrain.TerrainSpriteContentsMixin` | `SpriteContents` | `AnimatedSpriteHandle` duck; `@ModifyReturnValue createAnimationState` links state to owner |
| `terrain.TerrainSpriteAnimationStateMixin` | `SpriteContents$AnimationState` | `@ModifyReturnValue needsToDraw` (skip unused sprites), `drawToAtlas` HEAD flushes a pending frame |
| `terrain.TerrainItemFeatureSpriteUseMixin` | `feature.ItemFeatureRenderer` | `@ModifyArg` on `putBakedQuad` in `prepareMainSubmit`: stamps item sprites as in use |
| `terrain.TerrainBlockModelFeatureSpriteUseMixin` | `feature.BlockModelFeatureRenderer` | same, static `putQuad` |
| `terrain.TerrainMovingBlockFeatureSpriteUseMixin` | `feature.MovingBlockFeatureRenderer` | same, `putBakedQuad(PoseStack,…)` |
| `terrain.TerrainFluidStateModelSetAccessor` | `FluidStateModelSet` | `@Accessor modelByFluid`: fluid sprites are never skipped |
| `terrain.TerrainFogRendererAccessor` | `FogRenderer` | static `@Accessor fogEnabled` (fog culling off when fog is toggled off) |

Every mixin class name is prefixed `Terrain` so its simple name is unique. `EphemeraCommonMixinPlugin` keys disable and yield rules by simple name, and another worker already has an `engine.SpriteContentsMixin`.

Requires MixinExtras (bundled with Fabric Loader 0.20.1): `@WrapOperation`, `@ModifyExpressionValue`, `@ModifyReturnValue`, `@Local`, `@Share`.

## Stand-down rules (`EphemeraClientMixinPlugin.onLoad`)

Register `registerYield(name, "sodium")` for **all 17** mixins above. Sodium replaces the vanilla chunk pipeline and has its own visible-only animation, so these hooks would be dead code or would conflict with it. No other yields are needed: Fabric API was checked and is compatible.

Partial disabling is safe. If `-Dephemera.mixin.disable` (or a failed apply) removes some of these mixins, each feature checks once with `Class.isAssignableFrom` that its required ducks and markers are present, and falls back to vanilla if they're not:
- the batcher needs the mesh, uber-buffer and dispatcher ducks;
- visible-only animation needs the sprite ducks, the results duck, all three `ExternalSpriteUseHook` feature-renderer markers, and the batcher.

## `isConfigEnabled` / static init hooks

None. Nothing needs to be wired in `EphemeraClient`, and `isConfigEnabled` can keep returning `true`. Every feature reads `EphemeraCommon.config()` at runtime, so toggles take effect live without a restart:

| Config field (all also require `terrainEngineEnabled`) | Feature |
|---|---|
| `terrainEngineEnabled` | master switch + chunk batcher |
| `terrainFogOcclusion` | skip sections fully inside fog |
| `terrainBitmaskMeshing` | skip tesselation of buried solid blocks |
| `terrainFastTranslucentSort` | stable radix translucent sort |
| `terrainAnimateOnlyVisibleTextures` | visible-only animated sprite uploads |
| `terrainPrefixSumBiomeBlend`, `terrainDirectionalFaceCulling`, `terrainMultiDraw` | not wired yet (see below) |

Each feature also has a fail-soft latch (`TerrainFeatures.disable*`). The first unexpected exception logs once and turns the feature off for the session.

## What each optimization does

1. **`ChunkRenderBatcher`** (replaces `prepareChunkRenders`) produces exactly vanilla's `ChunkSectionsToRender`: same `combinedHash` grouping, the same ordered single translucent list, and the same UBO contents. It removes the per-frame `EnumMap`, 3× `Int2ObjectOpenHashMap` and `ArrayList` rebuilds, the 2× `HashMap.get` + `RenderSectionBufferSlice` per layer per section (`UberGpuBuffer.getAllocation` / `getRenderSectionSlice`, the measured 12.4% `HashMap.getNode` hotspot), the `new Matrix4f` per section, and `Util.getMillis()` per section. It also reuses `RenderPass.Draw` records, their uniform lambdas and `ChunkSectionInfo` records while a section's allocations and UBO slot are unchanged. Reads and writes of the mirrored allocations happen under the dispatcher's existing copy lock.
2. **Fog occlusion** skips a section when its padded (±1 block) box is past `environmentalEnd` spherically or past `renderDistanceEnd` cylindrically. This mirrors `fog.glsl`'s `max()` of the two terms. It only applies when fog is enabled and `FogColor.a == 1`.
3. **Buried-block skipping** in `SectionCompiler.compile`: a lazily built 18³ `BitmaskFaceCuller` stores, per block, which faces are exactly `Shapes.block()` (the test `Block.shouldRenderFace` uses). A block is skipped when all of these hold:
   - it is `isSolidRender`;
   - all six neighbours present a full face toward it;
   - its vanilla model has no unculled quads for its seed.

   A skipped block emits zero quads in vanilla too. VisGraph, block entities and fluids are unaffected. Non-vanilla (modded or FRAPI-wrapper) model classes are never skipped. Air-only sections never build the mask.
4. **Radix translucent sort**: same JOML distance as vanilla and a stable descending order, so the quad order is identical to vanilla's stable merge sort.
5. **Visible-only sprite animation** (block atlas only): an animation state's blit is skipped while its sprite has not been stamped within 200 ms by any of these:
   - a drawn section's model sprites;
   - an item, block-model or moving-block feature quad.

   A skipped frame change is redrawn the moment the sprite is used again. Sprites are always animated in these cases:
   - fluid sprites;
   - sprites never seen in a section model;
   - a visible section containing an untracked (non-vanilla) model;
   - the batcher hasn't run for 250 ms.

## Core (pure Java, `com.ephemera.core.terrain`, 30 JUnit tests passing)

`BitmaskFaceCuller`, `BiomePrefixBlender`, `RegionKey`, `RegionSlabAllocator`, `DirectionalFaceMask`, `FastFrustumMath`, `TranslucentBucketSorter`. They contain no MC, Fabric, LWJGL or Mixin imports.

## Not done / follow-ups (honest scope)

- **`BiomePrefixBlender`** is implemented and tested (bit-identical to the naive integer average) but not wired. Vanilla blends per position through `ClientLevel.calculateBlockTint` with a per-column `BlockTintCache`. Wiring it needs a per-section batch hook in `RenderSectionRegion`/`BlockTintCache`, and Fabric's block-view API also mixes into `RenderSectionRegion`.
- **`DirectionalFaceMask` / `RegionKey` / `RegionSlabAllocator`** are implemented and tested, not wired. Directional buckets need per-facing index ranges in the section mesh, and region slabs need a replacement for `UberGpuBuffer`/TLSF. Both are larger pipeline changes.
- **Flat `BufferBuilder[3]`** is intentionally not done. Vanilla's per-quad lookup is `EnumMap.get`, which is already an ordinal array index. A `ThreadLocal` or wrapper would cost more than it saves, and the compile body is shared with Fabric's redirect.
- **`drawIndexedIndirect` multi-draw** is not implemented. Removing the per-draw `ChunkSection` UBO bind needs shader changes (section data indexed by `gl_DrawID`/base instance). Shaders are outside this worker's paths.
- **Not runtime-tested**: per the rules, no `gradlew`/`runClient` was run. Verification is the `check_worker` compile and the JUnit run. A benchmark pass (`terrain_vista_rd20`, GL and Vulkan) and an animated-texture visual check (lava/prismarine in view, out of view, held in hand) are still needed.
