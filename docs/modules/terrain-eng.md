# terrain-eng — Core Terrain Rendering Engine & Bitmask Mesher

Owner: terrain-eng worker. Target: Minecraft 26.2 (Fabric Loader 0.20.1, Java 25), OpenGL and Vulkan (Blaze3D-level only; no backend-specific code).

All MC signatures were verified against the 26.2 client jar bytecode (`build/mc262-specs/terrain/*.txt` plus `javap` on `LevelRenderer`, `GameRenderer`, `DynamicUniformStorage`, `VertexSorting`, `Block`, `BlockStateBase`, the `feature.*FeatureRenderer`s, `FluidModel`, `BakedQuad`). Fabric API 0.161 mixins were scanned for overlapping targets; the only overlap is Fabric's `@Redirect` of `ModelBlockRenderer.tesselateBlock` inside `SectionCompiler.compile`. That redirect is why the compiler hooks here leave `compile` intact rather than replacing it.

## Mixins to register (relative to `com.ephemera.mixin.client`, `"client"` array)

| Mixin | Target | Purpose |
|---|---|---|
| `terrain.TerrainCompiledSectionMeshMixin` | `CompiledSectionMesh` | `SectionMeshRenderCache` duck: ordinal-indexed draws, allocation slots, precomputed `SectionDrawState` (Wave 3), Draw/UBO-record caches, sprite ids |
| `terrain.TerrainUberGpuBufferMixin` | `UberGpuBuffer` | `@WrapOperation` on `allocationMap.put/remove` mirrors allocations onto the mesh duck (vanilla map untouched) |
| `terrain.TerrainUberGpuBufferHeapAccessor` | `UberGpuBuffer$UberGpuBufferHeap` | `@Accessor gpuBuffer` |
| `terrain.TerrainSectionRenderDispatcherMixin` | `SectionRenderDispatcher` | tags the six uber buffers with slot and epoch at `<init>` RETURN; `dispose` HEAD releases the batcher's indirect buffers |
| `terrain.TerrainSectionUberBuffersAccessor` | `SectionRenderDispatcher$SectionUberBuffers` (private record, string target) | `@Accessor vertexBuffer/indexBuffer` |
| `terrain.TerrainLevelRendererChunkBatchMixin` | `LevelRenderer` | `prepareChunkRenders` HEAD, cancellable → `ChunkRenderBatcher`; vanilla runs when it returns null. `@ModifyExpressionValue` on `RenderSection.getRenderOrigin()` in the same method gives region-baked meshes their baked origin on the vanilla path. `clearVisibleSections` HEAD bumps `TerrainTopology.visibleSections()` (Wave 3). Implements the `BakedOriginAware` marker |
| `terrain.TerrainSectionCompilerMixin` | `SectionCompiler` | `@Inject` at `betweenClosed`, `@ModifyExpressionValue` on `BlockState.getRenderShape()` (buried → `INVISIBLE`), RETURN publishes sprite ids and runs `RegionVertexBaking` |
| `terrain.TerrainSectionCompilerResultsMixin` | `SectionCompiler$Results` | `CompileResultsSprites` + `CompileResultsLayout` ducks |
| `terrain.TerrainRenderSectionVertexSortingMixin` | `SectionRenderDispatcher$RenderSection` | `@WrapOperation` on `VertexSorting.byDistance(FFF)` in `createVertexSorting` → radix sorter; `setSectionMesh` RETURN bumps `TerrainTopology` (Wave 3, `SectionMeshSwapHook` marker) |
| `terrain.TerrainTextureAtlasMixin` | `TextureAtlas` | block atlas `upload` RETURN assigns animated sprite ids; `clearTextureData` HEAD resets |
| `terrain.TerrainSpriteContentsMixin` | `SpriteContents` | `AnimatedSpriteHandle` duck; `@ModifyReturnValue createAnimationState` links state to owner |
| `terrain.TerrainSpriteAnimationStateMixin` | `SpriteContents$AnimationState` | `@ModifyReturnValue needsToDraw` (skip unused sprites), `drawToAtlas` HEAD flushes a pending frame |
| `terrain.TerrainItemFeatureSpriteUseMixin` | `feature.ItemFeatureRenderer` | `@ModifyArg` on `putBakedQuad` in `prepareMainSubmit`: stamps item sprites as in use |
| `terrain.TerrainBlockModelFeatureSpriteUseMixin` | `feature.BlockModelFeatureRenderer` | same, static `putQuad` |
| `terrain.TerrainMovingBlockFeatureSpriteUseMixin` | `feature.MovingBlockFeatureRenderer` | same, `putBakedQuad(PoseStack,…)` |
| `terrain.TerrainFluidStateModelSetAccessor` | `FluidStateModelSet` | `@Accessor modelByFluid`: fluid sprites are never skipped |
| `terrain.TerrainFogRendererAccessor` | `FogRenderer` | static `@Accessor fogEnabled` (fog culling off when fog is toggled off) |
| **`terrain.TerrainChunkSectionsToRenderMixin`** (new, Wave 2) | `ChunkSectionsToRender` (record) | `renderGroup` HEAD, cancellable: executes the frame's `RegionalChunkBatchPlan` when this instance is the one it was built for; implements the `RegionalRenderHook` marker |
| **`terrain.TerrainRenderSectionFadeAccessor`** (new, Wave 2) | `SectionRenderDispatcher$RenderSection` | `@Accessor uploadedTime` and `fadeDuration`: settled sections skip `getVisibility` |

Every mixin class name is prefixed `Terrain` so its simple name is unique. `EphemeraCommonMixinPlugin` keys disable and yield rules by simple name, and another worker already has an `engine.SpriteContentsMixin`.

Requires MixinExtras (bundled with Fabric Loader 0.20.1): `@WrapOperation`, `@ModifyExpressionValue`, `@ModifyReturnValue`, `@Local`, `@Share`.

## Stand-down rules (`EphemeraClientMixinPlugin.onLoad`)

Register `registerYield(name, "sodium")` for **all 19** mixins above, including the two new Wave-2 mixins `TerrainChunkSectionsToRenderMixin` and `TerrainRenderSectionFadeAccessor`. Add both to the `"client"` array of `ephemera.client.mixins.json` as `terrain.TerrainChunkSectionsToRenderMixin` and `terrain.TerrainRenderSectionFadeAccessor`. Sodium replaces the vanilla chunk pipeline and has its own visible-only animation, so these hooks would be dead code or would conflict with it. No other yields are needed: Fabric API was checked and is compatible.

Partial disabling is safe. If `-Dephemera.mixin.disable` (or a failed apply) removes some of these mixins, each feature checks once with `Class.isAssignableFrom` that its required ducks and markers are present, and falls back to vanilla if they're not:
- the batcher needs the mesh, uber-buffer and dispatcher ducks;
- visible-only animation needs the sprite ducks, the results duck, all three `ExternalSpriteUseHook` feature-renderer markers, and the batcher;
- vertex baking (region-relative positions and directional buckets) needs the `CompileResultsLayout` results duck, the `BakedOriginAware` marker on `LevelRenderer` (so baked meshes stay correctly placed if the batcher is bypassed), and the batcher ducks;
- regional multi-draw needs the batcher, the `RegionalRenderHook` marker on `ChunkSectionsToRender`, and a device reporting `multiDrawDirectSeparate || (drawIndirect && multiDrawIndirect)`;
- frame-plan reuse additionally needs the `SectionMeshSwapHook` marker on `RenderSection` (otherwise every frame rebuilds);
- without `TerrainRenderSectionFadeAccessor` the batcher just calls `getVisibility` for every section.

## `isConfigEnabled` / static init hooks

None. Nothing needs to be wired in `EphemeraClient`, and `isConfigEnabled` can keep returning `true`. Every feature reads `EphemeraCommon.config()` at runtime, so toggles take effect live without a restart:

| Config field (all also require `terrainEngineEnabled`) | Feature |
|---|---|
| `terrainEngineEnabled` | master switch + chunk batcher |
| `terrainMultiDraw` | region-relative vertex baking (compile time) + regional multi-draw (render time: direct `multiDrawIndexed` on GL, `drawIndexedIndirect` on Vulkan) |
| `terrainDirectionalFaceCulling` | 7-bucket quad reordering (compile time) + skipping back-facing buckets (render time) |
| `terrainFogOcclusion` | skip sections fully inside fog |
| `terrainBitmaskMeshing` | skip tesselation of buried solid blocks |
| `terrainFastTranslucentSort` | stable radix translucent sort |
| `terrainAnimateOnlyVisibleTextures` | visible-only animated sprite uploads |
| `terrainPrefixSumBiomeBlend` | not wired yet (see below) |

The compile-time halves of `terrainMultiDraw` and `terrainDirectionalFaceCulling` apply to sections compiled after a toggle. Meshes that already exist keep rendering correctly with their own recorded layout (see "Fallbacks"), they just don't get the new optimization until they recompile. If a settings screen wants the change applied everywhere at once, it can call `levelRenderer.allChanged()` on apply.

Each feature also has a fail-soft latch (`TerrainFeatures.disable*`, including `disableMultiDraw` and `disableBaking`). The first unexpected exception logs once and turns the feature off for the session.

## What each optimization does

1. **`ChunkRenderBatcher`** (replaces `prepareChunkRenders`). Its per-section fallback mode produces vanilla's `ChunkSectionsToRender`: the same `combinedHash` grouping, the same single ordered translucent list, and the same UBO contents. The one difference is that baked meshes get their baked origin. Compared with vanilla it removes the per-frame `EnumMap`, 3× `Int2ObjectOpenHashMap` and `ArrayList` rebuilds, the 2× `HashMap.get` + `RenderSectionBufferSlice` per layer per section (`UberGpuBuffer.getAllocation` / `getRenderSectionSlice`, the measured 12.4% `HashMap.getNode` hotspot), the `new Matrix4f` per section, and `Util.getMillis()` per section. It also reuses `RenderPass.Draw` records, their uniform lambdas and `ChunkSectionInfo` records while a section's allocations and UBO slot don't change. Reads and writes of the mirrored allocations happen under the dispatcher's existing copy lock. The regional mode is described under "Wave 2" below.
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

## Wave 2: regional multi-draw indirect + 7-bucket directional culling

Wave-1 JFR on `terrain_vista_rd20` found three costs:
- About 2,000 `drawIndexed` calls per frame. Each had its own `ChunkSection` UBO, and on Vulkan its own `pushDescriptors`.
- About 45% of the opaque quads sent to the rasterizer faced away from the camera.
- `getVisibility` was called on every section, costing 6.3% CPU on Vulkan. In Wave 1 most of that came from the visuals `RenderSectionFadeMixin` `@Inject` allocating a `CallbackInfoReturnable` on every call. That mixin now uses `@ModifyReturnValue`, and settled sections skip the call entirely either way.

Wave 2 removes all three. It changes no shaders, needs no extra VRAM, and uses only the Blaze3D API. `RenderPass.drawIndexedIndirect` becomes `glMultiDrawElementsIndirect` on GL and `vkCmdDrawIndexedIndirect` on Vulkan, both with the same 20-byte command layout (verified in `GlCommandEncoder.executeDrawIndirect` and `VulkanRenderPass.drawIndexedIndirect`).

### Off-thread vertex baking (`RegionVertexBaking`, core `SectionVertexBaker`)

Runs at `SectionCompiler.compile` RETURN on the chunk-builder thread. It only touches layers in `DefaultVertexFormat.BLOCK` (28-byte stride, `float3` position at offset 0).

- **Region-relative positions** (when `terrainMultiDraw` is on):
  - Every vertex of every layer gets `sectionOrigin - regionOrigin` added. Regions are 16×8×16 sections (256×128×256 blocks), and `regionOrigin = (x & -256, y & -128, z & -256)`. That mask floors correctly for negative coordinates: −16 → −256, −272 → −512, y −64 → −128.
  - With `ChunkPosition = regionOrigin`, the shader's `Position + (ChunkPosition - CameraBlockPos)` gives the same world position as before.
  - Baked coordinates stay within [−1, 257], where a float still resolves 1/32768 of a block.
  - Baking is all-or-nothing per section, because all layers share one `ChunkSection` UBO.
- **Translucent** is only rebased; quad order is untouched. Its `SortState` centroids are taken before the bake, so they stay section-local, and `ResortTransparencyTask` still produces the same order.
- **Directional buckets** (when `terrainDirectionalFaceCulling` is on). This only applies to sequentially indexed `QUADS` layers, which means solid and cutout.
  - Each quad is classified by its triangle winding. The quad must lie exactly (within 1e-5) in an axis-aligned plane, and both `(v1-v0)×(v2-v0)` and `(v3-v2)×(v0-v2)` must agree along that axis.
  - The buckets are `DOWN, UP, NORTH, SOUTH, WEST, EAST, UNASSIGNED`, numbered as `Direction` ordinals. Since Wave 3 they are *stored* in the symmetric slot order `EAST, UP, SOUTH, UNASSIGNED, NORTH, DOWN, WEST` (see below).
  - Diagonal, non-planar, degenerate and bow-tie quads go to `UNASSIGNED`, which is always drawn.
  - The classification uses the same winding the GPU culls on, so a quad is only skipped when the GPU would have discarded it anyway.
  - Checked against the 26.2 `FaceInfo` vertex order (javap of `<clinit>`): every face winds outward. Unit tests cover all six faces, cyclic rotations, translations, reversed winding, and diagonal, degenerate and bow-tie quads.
  - Quads are reordered into contiguous buckets with a stable counting sort, so coplanar overlays keep their order. Scratch arrays are per thread.
  - The result, `QuadBuckets`, stores `offsets[8]` (quad offsets) and `planes[6]`: the lowest plane of each positive-facing bucket and the highest of each negative-facing one, section-local.
- The result is a `SectionLayout` (baked origin, `regionRelative`, buckets). It travels on `SectionCompiler$Results` (`CompileResultsLayout`) to `CompiledSectionMesh` (`SectionMeshRenderCache.ephemera$layout()`).
- Every mesh remembers the origin it was baked against, and every draw path uses that origin: regional, per-section fallback, and vanilla `prepareChunkRenders` via the `getRenderOrigin` fix. Toggling `terrainMultiDraw` live therefore never misplaces existing chunks.

### Per-frame planning (`ChunkRenderBatcher.prepareRegional` → `RegionalChunkBatchPlan`)

- **Settled check**: reads `now - uploadedTime >= fadeDuration` through `TerrainRenderSectionFadeAccessor`, which is exactly the condition under which vanilla returns `1.0f` early. `getVisibility` (and the injected ease-out) therefore only runs while a section is fading. No latch is needed.
- **UBOs**: settled, region-baked sections share one `ChunkSectionInfo` per region (region origin, visibility 1). Fading or unbaked sections get their own at their baked origin. All are written in a single `writeChunkSections` call, typically ~20 UBOs instead of ~2,000.
- **Solid/cutout**:
  - The visible mask comes from `QuadBuckets.visibleMask(cam - sectionOrigin, margin 0.5)` against the measured planes. A +X bucket counts as visible only if `camX > minPlane - 0.5`, and a −X bucket only if `camX < maxPlane + 0.5`. The margin covers view-bob eye offsets (about 0.1 block) and float rounding.
  - Culling is skipped when the layer's (or wireframe) pipeline has `isCull() == false`.
  - Visible buckets coalesce into at most 3 spans since Wave 3 (4 before). An empty bucket never splits a span.
  - Each span becomes one command: `indexCount = quads*6, instanceCount = 1, firstIndex = 0, baseVertex = sectionBase + startQuad*4, firstInstance = 0`. The shared `AutoStorageIndexBuffer` is sized to the largest span.
  - Batches are keyed by (UBO, vertex heap) and kept in first-seen (near-to-far) order.
- **Translucent**: draws are staged in visible order and walked in reverse, which is vanilla's `list.reversed()` back-to-front order. Consecutive draws that share (UBO, vertex heap, index heap, index type) merge into one run, with `firstIndex = indexOffset / bytes` and `baseVertex = sectionBase`.
- **Upload**:
  - All batches' commands are laid out contiguously and uploaded with `CommandEncoder.writeToBuffer` before any render pass opens.
  - They go into a 3-slot ring of `USAGE_INDIRECT_PARAMETERS | USAGE_COPY_DST` buffers (`IndirectBufferRing`). Slots grow lazily to a power of two and the old buffer is closed.
  - The ring is released on `SectionRenderDispatcher.dispose`, and its buffers are dropped when the `GpuDevice` instance changes.

### Execution (`TerrainChunkSectionsToRenderMixin` → `RegionalChunkBatchPlan.render`)

`RegionalChunkBatchPlan.render` follows vanilla `renderGroup` step by step:
- same `createRenderPass` label and targets;
- `bindDefaultUniforms`, then `Sampler0` and `Sampler2`;
- the `DEBUG_HOTKEYS && wireframe` pipeline switch;
- the layer loop.

Inside the loop, each batch sets the index buffer, the `ChunkSection` uniform and the vertex buffer, but only when they changed. It then issues one `drawIndexedIndirect(slice, count)`, or a plain `drawIndexed` for a single-command batch.

The returned `ChunkSectionsToRender` carries empty draw groups. The hook only acts on the exact instance the plan was built for, and every other instance runs the vanilla body.

### Fallbacks

- **Per-section path.** Runs when `terrainMultiDraw` is off, when the device has neither `multiDrawDirectSeparate` nor `drawIndirect`+`multiDrawIndirect` (checked per `GpuDevice` instance), or after a multi-draw failure. It still uses each mesh's baked origin. With culling on, each solid/cutout draw is trimmed to the single range covering the visible buckets (`QuadBuckets.coveringSpan`), so there is still one draw per section-layer.
- **Batcher off or failed.** Vanilla `prepareChunkRenders` runs, with the baked-origin fix applied.
- **Baking failure.** The latch turns baking off. The baker only writes back on success, so a layer that failed is untouched and gets retried offset-only, which keeps a single origin per mesh.

### Compatibility notes

- Shaders and resource-pack shaders are unchanged, and `Position + ChunkPosition` is still the world position. Anything that uses the world position (fog, dithering, anisotropy, waving by world position) is unaffected. A custom shader that treats `Position` alone as section-local, for example `fract(Position / 16)` for per-section effects, would see region-local values instead.
- The visuals `RenderSectionFadeMixin` still applies to fading sections. The settled shortcut only covers the case where vanilla returns exactly 1.

## Wave 3: direct GL multi-draw, precomputed draw state, frame-plan reuse

Wave-2 JFR showed two remaining costs. On OpenGL, uploading the indirect buffer every frame (`writeToBuffer` + `glMultiDrawElementsIndirect`) stalled the AMD driver, so GL `terrain_vista_rd20` stayed at 838.6 FPS while Vulkan gained 23%. On both backends, `ChunkRenderBatcher.prepareRegional` took 21–23% inclusive CPU (14–15% self).

**No new mixin classes.** The two new injectors were added to mixins that are already registered:

| Mixin (existing, already registered) | New injector | Purpose |
|---|---|---|
| `terrain.TerrainLevelRendererChunkBatchMixin` | `clearVisibleSections` HEAD | bumps `TerrainTopology.visibleSections()`. `LevelExtractor.applyFrustum` clears the list before every refill, and `invalidateCompiledGeometry` / `resetLevelRenderData` clear it too (verified with javap on the 26.2 jar) |
| `terrain.TerrainRenderSectionVertexSortingMixin` | `setSectionMesh` RETURN | bumps `TerrainTopology.topology()`. Also implements the new `SectionMeshSwapHook` marker |

Neither `ephemera.client.mixins.json` nor `EphemeraClientMixinPlugin` needs any change; both mixins already yield to `sodium`.

### Direct multi-draw on OpenGL (`RegionalChunkBatchPlan`, `TerrainFeatures.directMultiDraw`)

- When `DeviceFeatures.multiDrawDirectSeparate()` is true, batches execute as `RenderPass.multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer baseVertices, int drawCount)`. GL reports this feature; Vulkan's `VulkanRenderPass` throws for it.
  - The argument order was verified against `RenderPass`, `GlRenderPass.multiDrawIndexed` and `GlCommandEncoder.executeDraws`. The call becomes `nglMultiDrawElementsBaseVertex(mode, memAddress(indexCounts), toGl(pass.indexType), memAddress(firstIndexOffsets), drawCount, memAddress(baseVertices))`.
  - `firstIndexOffsets` holds byte offsets, `firstIndex * indexType.bytes`. Sequential solid/cutout draws use `0`, and translucent draws use the index allocation's byte offset.
- The three arrays are off-heap LWJGL buffers (`memAllocPointer` / `memAllocInt`). They grow to a power of two (at least 1,024 commands) and are freed in `release()`, which runs on dispatcher dispose. They are filled with absolute `put(index, value)` calls; each batch sets the three buffers' positions to its `firstCommand` before drawing.
- GL reads client-memory draw parameters at call time. So on GL there is no indirect buffer, no `writeToBuffer` upload, no `GL_DRAW_INDIRECT_BUFFER` bind and no ring.
- Devices without the feature (Vulkan) keep `drawIndexedIndirect`. Commands are written straight into `IndirectBufferRing`'s staging buffer with absolute native-order writes (`IndirectCommandList.writeTo(ByteBuffer, offset)`, a `VarHandle`), with no `merged` intermediate list and no `slice()`/`asIntBuffer()` views. Each frame makes exactly one `writeToBuffer` upload.
- Multi-draw availability is now `multiDrawDirectSeparate || (drawIndirect && multiDrawIndirect)`. The startup log names the mode in use.
- `-Dephemera.terrain.forceIndirect=true` keeps the indirect path even where direct multi-draw is available, for A/B testing. `RegionalChunkBatchPlan.lastDirect()` reports which mode was used.

### Precomputed per-layer draw state (`SectionDrawState`)

- `TerrainCompiledSectionMeshMixin.ephemera$setAllocation` runs under the dispatcher's copy lock. It now recomputes the affected layer of a per-epoch `SectionDrawState`:
  - `vertexBuffers[3]`;
  - `baseVertices[3]` (`offset / vertexSize`, with the stride resolved once from the static section pipelines);
  - `indexBuffers[3]`, `indexTypes[3]`, `firstIndices[3]`, `indexCounts[3]`;
  - `cullBuckets[3]` (buckets that cover exactly the draw);
  - `readyMask`, where bit `l` means a draw exists, its vertices are uploaded and (for a custom index) its indices are uploaded;
  - `customIndexMask`.
- `close()` clears the state.
- `prepareRegional` reads `state.readyMask` once and loops only over its set bits. It no longer does any `TlsfAllocator.Allocation` lookups, `heap → gpuBuffer` accessor calls, 64-bit divisions or empty-layer iteration.
- `SectionLayout.regionKey` is a `final` field computed in the constructor.

### Regional slot and batch lookup

- **Region slot cache.** `prepareRegional` remembers the last region key and its UBO slot, so consecutive sections in the same region cost one `long` compare. Misses fall back to `Long2IntOpenHashMap`.
- **Region info reuse.** `ChunkSectionInfo` records for regions are cached per region key (`regionInfos`, capped at 4,096) and reused while the atlas size is unchanged. They all reference the batcher's single `frameModelView`, so steady state allocates no `new ChunkSectionInfo`.
- **Flat batch table.** For sequential-index draws (`indexBuffer == null`) with `ubo < 256` and heap id `< 4`, the batch comes from a direct `Batch[1024]` table indexed by `(ubo << 2) | heapId`. Only touched slots are cleared, in O(batches). `Long2ObjectOpenHashMap byKey` is now only the overflow path, for larger slots, more heaps or custom index buffers. A one-entry heap-id cache short-cuts the heap linear scan.
- **Batch pool.** The pool is capped at 4,096 batches per layer. That covers frames where many sections are still fading in, each with its own UBO, so steady state allocates no `new Batch`.

### Symmetric bucket order (`QuadBuckets`, `SectionVertexBaker`)

- Quads are stored in slot order **`[EAST, UP, SOUTH, UNASSIGNED, NORTH, DOWN, WEST]`**. Bucket ids and `planes[]` keep their `Direction` ordinals; only the storage order changed. `start/end/count(bucket)` take bucket ids, while `offsets()` is in slot order. `QuadBuckets.slotOf` and `bucketAt` map between the two.
- Opposite facings sit mirrored around the always-drawn centre. So any mask that includes `UNASSIGNED` (every `visibleMask` result) forms at most **3** spans, `MAX_VISIBLE_SPANS` (it was 4). Arbitrary masks are still capped at `MAX_SPANS = 4`.
- Counting spans over the 8 octants a camera outside a section can be in:

  | Octants | Spans each |
  |---|---|
  | +X+Y+Z and −X−Y−Z | 1 |
  | 4 others | 2 |
  | +X−Y+Z and −X+Y−Z | 3 |

  That is 16 spans in total, against 24 with the old `Direction`-ordinal order.
  - This is not "almost always 1". Cameras above terrain usually look down on the +Y side, and those octants take 1–3 spans.
  - Cameras inside a section's slab on an axis see both facings of that axis, and those cases coalesce further.
- `spans` and `coveringSpan` are now bitmask-based: a 128-entry bucket→slot mask table, an empty-slot mask, and runs found with `numberOfTrailingZeros`.
- Meshes compiled before this change don't exist across sessions, since layouts are not persisted, so there is no migration.

### Frame-plan reuse (`ChunkRenderBatcher.canReusePlan` / `reuseRegional`, `TerrainTopology`)

When a regional plan was built, the batcher remembers what it was built from. The next frame reuses the plan, without touching any section, only if **all** of these hold:

- **Same dispatcher state**: the same dispatcher and epoch.
- **No topology change**: `TerrainTopology.topology()` is unchanged. It is bumped on every mirrored allocation put/remove, every mesh `close()` that had draws, every `RenderSection.setSectionMesh`, and `release()`.
- **No visible-list change**: `TerrainTopology.visibleSections()` is unchanged, the list has the same size, and an element-by-element identity check of the list passes. The identity check costs about 2,500 reference compares and doesn't dereference sections.
- **Exact same camera and fog**: the same `hasCamera`, the exact same `camX/camY/camZ` doubles, and the same `fogCull`, `envEnd` and `rdEnd`.
- **Same rendering settings**: the same cull-layer mask (directional-culling toggle, wireframe, `isCull`), the same atlas width and height, the same sprite-tracking flag, and the same direct/indirect mode.
- **No fading sections**: none of the sections in the plan was fading when it was built. While any section fades, every frame rebuilds.
- **Plan reuse enabled**: `TerrainFeatures.planReuse()` is true. That requires the `SectionMeshSwapHook` marker and is turned off by `-Dephemera.terrain.planReuse=false`.

The two counters are read **before** the build loop. So a change that races a build only causes one extra rebuild and never a stale reuse.

A reuse frame does the following:
- writes this frame's `ChunkSection` uniforms (`writeChunkSections(exactInfos)`, with the new model-view, since dynamic-uniform slices are per frame);
- re-arms the same plan.
  - On GL it reuses the filled client-memory arrays.
  - On Vulkan it reuses the already-uploaded indirect `GpuBuffer`, with no upload. Reusing one ring slot across frames is safe: nothing is committed in between, and the next commit still goes to a slot last used three or more commits ago.
- re-stamps animated sprites every 50 ms. During the build it records the meshes whose sprites need stamping (animated ids, untracked models, or a generation mismatch). The tracker's grace window is 200 ms, so lava, water and prismarine never freeze.

`ChunkRenderBatcher.reusedFrames()` counts reuse frames. `terrain_vista_rd20` rotates continuously, so it mostly rebuilds (on the faster rebuild path). Stationary scenes such as `dense_world_crowd` and `gui_pvp_hud` should mostly reuse.

### Wave 3 verification

- `check_worker` compile is OK (60 files).
- 62/62 core terrain JUnit tests pass. New tests:
  - `QuadBucketsTest`: slot order and mirror symmetry; bucket-id accessors against slot offsets; 1-span positive and negative octants; 2- and 3-span mixed octants; the 16-span octant total; and an exhaustive brute-force check of every one of the 128 masks over 200 random layouts. That check covers exact quad coverage, fewest spans, ascending non-touching spans, the ≤3 bound with `UNASSIGNED`, and `coveringSpan`.
  - `QuadBucketsTest` also covers that empty buckets bridge spans, and that the absolute `writeTo(ByteBuffer, offset)` is independent of byte order and position.
  - `SectionVertexBakerTest`: the new reorder order and the baked-offset order.
- **Not runtime-tested.** Per the rules, no `gradlew`/`runClient` was run. Still to check:
  - GL `terrain_vista_rd20` FPS and JFR (`prepareRegional` share, and that there is no `writeToBuffer`);
  - Vulkan the same;
  - `reusedFrames()` climbing in `dense_world_crowd` and `gui_pvp_hud`;
  - visual checks of chunk loading and fade-in while stationary, block place and break while stationary (topology bump), translucent ordering, and animated textures in view while stationary.

## Core (pure Java, `com.ephemera.core.terrain`, 62 JUnit tests passing)

| Class | Notes |
|---|---|
| `BitmaskFaceCuller` | |
| `BiomePrefixBlender` | |
| `RegionKey` | now 16×8×16 sections, with block-origin helpers |
| `RegionSlabAllocator` | |
| `DirectionalFaceMask` | bucket ids are now `Direction` ordinals; adds section and plane masks |
| `QuadBuckets` | new; Wave 3: symmetric slot order, bitmask span coalescing |
| `SectionVertexBaker` | new; Wave 3: writes the symmetric slot order |
| `IndirectCommandList` | new; Wave 3: allocation-free absolute `writeTo(ByteBuffer, offset)` |
| `FastFrustumMath` | |
| `TranslucentBucketSorter` | |

None of them import MC, Fabric, LWJGL or Mixin classes.

## Not done / follow-ups (honest scope)

- **`BiomePrefixBlender`** is implemented and tested (bit-identical to the naive integer average) but not wired. Vanilla blends per position through `ClientLevel.calculateBlockTint` with a per-column `BlockTintCache`. Wiring it needs a per-section batch hook in `RenderSectionRegion`/`BlockTintCache`, and Fabric's block-view API also mixes into `RenderSectionRegion`.
- **`RegionSlabAllocator`** is implemented and tested but not wired. It isn't needed for multi-draw: vanilla `UberGpuBuffer` heaps already put every section into one or two 64 MB buffers, and the regional batches draw from those directly.
- **Flat `BufferBuilder[3]`** is intentionally not done. Vanilla's per-quad lookup is `EnumMap.get`, which is already an ordinal array index. A `ThreadLocal` or wrapper would cost more than it saves, and the compile body is shared with Fabric's redirect.
- **Per-draw section data via `gl_DrawID`/base instance** is not done. Region baking already removes the per-section UBO without shader changes, leaving one bind per region. Fading sections still need one UBO each, usually 0–10 per frame.
- **Bucket order**: done in Wave 3 (symmetric slot order, at most 3 spans; see Wave 3).
- **Not runtime-tested**: per the rules, no `gradlew`/`runClient` was run. Verification is the `check_worker` compile and the JUnit run. Still needed:
  - a benchmark pass on `terrain_vista_rd20` (GL and Vulkan), reading `ChunkRenderBatcher.lastSectionCount()`, `plan().lastBatchCount()` and `plan().lastCommandCount()`;
  - a visual check for cracks at region borders (x/z multiples of 256, y multiples of 128, including negative coordinates);
  - a visual check for missing faces while flying around and through terrain;
  - a visual check of translucent ordering (water, stained glass) and of chunk fade-in;
  - an animated-texture check (lava/prismarine in view, out of view, held in hand).
