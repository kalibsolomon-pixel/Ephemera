# Entity, block-entity, particle and block-state culling

Targets unobfuscated Minecraft 26.2, Java 25 and Fabric Loader 0.20.1. This module owns four pure Java core classes, five test/suite classes, six client helpers and 27 client mixins/accessors. Shared manifests, the mixin plugin, config schema, access widener and client initializer are integration-owned and were not edited.

## Registration and init

Call this once from `EphemeraClient.onInitializeClient`, after the API service is installed:

```java
com.ephemera.render.culling.EphemeraVisibilityService.init();
```

It calls `EphemeraClient.setVisibilityProvider(...)`. Aurora receives conservative section/frustum and box-occlusion queries, the terrain revision, the last completed frame's rendered-section count and entity/block-entity rejection counters. Queries from another thread, a different world, before camera initialization or after the camera moved fail open. This provider does not replace the terrain renderer's section occlusion graph.

Every name below is relative to **`com.ephemera.mixin.client`**, including its `culling.` or `particle.` prefix. Register `yieldsTo` by the simple class name in `EphemeraClientMixinPlugin`; apply the rules when `modStandDownEnabled` is true. The table is the complete client manifest for this module. All functional paths also read the live `EphemeraCommon.config()` flags.

| Mixin | Config / registration predicate | yieldsTo | Target and behavior |
| --- | --- | --- | --- |
| `culling.LevelExtractorCullingMixin` | Always; shared lifecycle | none | Begins extraction frame, records rendered-section count, releases section references and resets on `setLevel`. |
| `culling.ClientLevelCullingMixin` | Always; shared invalidation | none | Invalidates block/section/chunk changes, local/predicted writes, light work and tick leash-holder state. |
| `culling.ClientPacketCullingMixin` | Always; shared invalidation | none | Invalidates section/chunk packet changes; tracks entity-link packets immediately. |
| `culling.LeashDataAccessor` | Always; lifecycle dependency | none | Reads unresolved `LeashData.delayedLeashHolderId` without allocating or resolving entity references. |
| `culling.RendererTypeCacheMixin` | Always; renderer cache dependency | none | Adds direct renderer/dispatcher/generation fields to both `EntityType` and `BlockEntityType`. |
| `culling.EntityRendererAccessor` | Always; entity culling dependency | none | Invokes renderer-specific culling bounds; reads the base shadow radius. |
| `culling.EntityRendererCacheMixin` | `entityRendererDirectCache` | `badoptimizations` | Both dispatcher renderer lookups use direct type fields; avatar/player model-specific map paths remain intact. Reload eagerly clears old renderer references and changes the generation. |
| `culling.BlockEntityRendererCacheMixin` | `blockEntityRendererDirectCache` | `badoptimizations` | Both block-entity dispatcher lookups use direct type fields and invalidate on resource reload. |
| `culling.EntityOcclusionMixin` | `entityOcclusionCulling` | `entityculling` | Rejects only vanilla-visible entities at `EntityRenderDispatcher.shouldRender` return, before extraction. |
| `culling.BlockEntityOcclusionMixin` | `blockEntityOcclusionCulling` | `entityculling` | Rejects proven hidden supported block entities before allocating their render state. Global/offscreen renderers are excluded. |
| `culling.ShadowGroundCacheMixin` | `entityRendererDirectCache` | `badoptimizations` | Caches static ground-surface classification at the actual 26.2 sampling site, `EntityRenderer.extractShadowPiece`. |
| `culling.ShadowFeatureRendererMixin` | `entityRendererDirectCache` | `badoptimizations` | Reuses exact full-cube bounds in `ShadowFeatureRenderer.prepare`; other shapes use vanilla bounds. |
| `culling.LeafBlockCullingMixin` | `blockStateCulling` | `moreculling` | Skips leaf tessellation only with a complete opaque enclosure; respects the renderer's `cull` flag. |
| `culling.LeavesOpaqueFaceMixin` | `blockStateCulling` | `moreculling` | Early return for already opaque adjacent leaves in `FAST_OPAQUE`; cutout leaves remain cutout. |
| `culling.SignTextCullingMixin` | `blockStateCulling && signTextBackfaceCulling` | `moreculling` | Tests the transformed text plane in `AbstractSignRenderer.submitSignText`, including hanging and rotated signs; preserves the sign model. |
| `culling.DecorationCullingMixin` | `blockStateCulling && (itemFrameCulling \|\| paintingBackfaceCulling)` | `entityculling`, `moreculling` | Coarse extraction culling for back-facing frames/paintings requires full-box opaque occlusion as well. |
| `culling.WeatherColumnAccessor` | Always; weather dependency | none | Primitive access to the private 26.2 column record's x/z/bottom/top fields. |
| `culling.WeatherCullingMixin` | `blockStateCulling && weatherCulling` | `moreculling` | Rejects whole rain/snow columns from output lists using expanded bounds, frustum and opaque-layer proof. |
| `culling.CloudFaceCullingMixin` | `blockStateCulling && cloudFaceCulling` | `moreculling` | Preserves 26.2's native hidden-face decisions and avoids a direction-array clone for each nearby extruded cloud cell. |
| `particle.CameraDepthAccessor` | Always; particle dependency | none | Reads the actual camera far depth. |
| `particle.FrustumAccessor` | Always; visibility/particle/weather dependency | none | Invokes primitive `cubeInFrustum(DDDDDD)`; no temporary `AABB`. |
| `particle.ParticleAccessor` | Always; particle dependency | none | Primitive current/previous particle coordinates. |
| `particle.SingleQuadParticleAccessor` | Always; particle dependency | none | Invokes `getLayer()` for a fixed-size, identity-keyed sheet counter. |
| `particle.ParticleEngineCullingMixin` | Always; light/sheet lifecycle | none | Begins/ends extraction epochs and clears sheet references on extraction end, clear or world change. |
| `particle.QuadParticleCullingMixin` | `particleCulling` | `particle_core`, `badoptimizations` | Replaces point rejection with interpolated quad bounds, actual far-depth distance, frustum and optional opaque occlusion before `SingleQuadParticle.extract`. |
| `particle.SingleQuadParticleRotationMixin` | `particleCulling` | `particle_core`, `badoptimizations` | Reuses a thread-local quaternion for standard vanilla quad extraction; unknown extraction contracts retain vanilla allocation. |
| `particle.ParticleLightmapMixin` | `particleLightmapCache` | `particle_core`, `badoptimizations` | Overwrites only the vanilla base particle light method with the same per-block lookup using primitive cache keys and a reusable position. Subclass light overrides still dispatch normally. |

Keep dependencies and shared lifecycle mixins registered even when an individual feature stands down. In particular, `FrustumAccessor` also serves the visibility API and weather, and terrain invalidation serves both particles and shadows. No gameplay/entity ticking is canceled.

## Core algorithms and cache contracts

- `OpaqueLayerSweep`: tests full voxel layers strictly between an expanded camera cube and expanded target AABB. At each candidate plane it computes all perspective corner extrema, then requires every covered voxel to be static, full and opaque. A missing cell immediately defeats that layer. Three separating-axis attempts share a 512-read budget and at most 32 layers per axis. Overlap, short axis gaps, invalid bounds, unloaded chunks and exhausted budgets retain visibility. This is a conservative sufficient proof; it intentionally misses some occluded objects.
- `DirectMappedSweepCache`: 1,024 entity and 512 block-entity slots, plus a shared 1,024-slot box-query cache for particles, weather and API queries. It matches the complete primitive ID, terrain revision, three camera coordinates and six bounds coordinates, with a non-sliding 250 ms refresh interval. Hash collisions only evict. Camera cells are quarter-block cells expanded by 1/8 block; bounds are rounded outward to quarter blocks before both testing and caching. A result applies to the entire cell envelope, not just the original sample position.
- `ShadowGroundCacheCore`: 4,096 primitive `(x,y,z,terrainStamp)` slots, with full key comparisons. Client payloads classify a static full cube, an invisible surface or a vanilla fallback. No chunk, shape or entity references are stored in the core.
- `LeafCullingPolicy`: `OFF` adds no rejection. `CHECK_SURROUNDING` requires six opaque enclosing neighbors. `DEPTH` requires that enclosure plus the configured number of proven opaque axial layers, clamped to 1–8. `FAST_OPAQUE` requires opaque leaf rendering and an adjacent leaf. Multiple cutout leaf layers are never treated as opacity proof.

## Client behavior and lifetimes

`EntityOcclusionManager` reads block states directly from `LevelChunkSection`. Its one-entry section cache sits in front of an 8,192-slot exact-key solidity table tagged with frame and terrain revision. Frame end invalidates the table logically; each sweep also releases the section reference in `finally`. No chunk or section survives a completed query. Block updates, successful local/predicted writes, section-dirty calls, section packets, chunk replacement/load/unload and level reset advance the revision immediately. Unknown/dynamic/offset shapes, partial blocks, glass, fluids and cutout leaves do not serve as occluders.

Entity exclusions are evaluated before every cached result: names, name displays, glowing tags/outlines, teams, riders/vehicles, leash sources and resolved/unresolved leash holders, fishing hooks, guardians, frogs and bosses remain visible. Only supported vanilla mob/item/frame/painting renderers enter entity occlusion; unusual large bounds and external renderer classes fail open. Bounds cover previous/current positions, animation padding and the complete shadow footprint. Standard vanilla shadow-radius contracts use their exact base/scale/age-scale calculation; an unknown radius override uses the vanilla 32-block cap. Supported block entities are vanilla chests, ender chests, signs, shulker boxes and banners, with a generous envelope covering lids and adjacent chest geometry. Beacons, global renderers and external renderers stay visible.

`FastShadowCache` skips palette and shape sampling on full-cube/invisible-surface hits. It still evaluates live local brightness, dimension brightness and precisely the vanilla floating-point entity alpha each time, and still emits the vanilla `ShadowPiece`. Dynamic, offset and non-cube surfaces retain vanilla extraction. Full-cube shape bounds are reused separately in `ShadowFeatureRenderer.prepare`. The existing `entityRendererDirectCache` flag gates both shadow hooks because this schema has no separate shadow-cache flag.

`ParticleCullingManager` performs steady-state culling without boxed coordinates, new boxes, callback objects or capturing lambdas per particle. It uses an interpolated sphere enclosing the complete rotated quad; far-depth rejection is disabled for captured or panoramic cameras. Standard vanilla extraction reuses a quaternion. Overridden/custom extraction contracts fail open. The 4,096-slot base light cache compares exact coordinates and extraction epoch, including full y values rather than truncated packed keys; terrain and light work invalidate it. Unknown sheets beyond the fixed 64-sheet table retain vanilla behavior. `particleOcclusionCulling` controls only the opaque proof, independently of the frustum/distance path.

`maxParticlesPerSheet` is an explicit output budget when `particleCulling` is enabled. The default 16,384 matches the vanilla group's maximum, so it does not remove visible vanilla particles. A user-configured lower positive limit deliberately limits output for that sheet; non-positive values disable the extra limit. This configured quality limit is separate from the conservative geometric rejection.

`BlockStateCullingManager` never treats back-facing frames or paintings as intrinsically invisible: their backs, edges and contents may be visible without backing terrain. It requires an opaque proof for the whole expanded box before skipping extraction. This is a coarse extraction LOD; vanilla distance behavior is retained and visible framed items/maps are never dropped just for being small or distant. Sign text has its own transformed-plane rejection with a near-plane margin. Weather keeps column generation/random behavior and filters only the resulting column list before rendering.

Minecraft 26.2 already rejects hidden cloud faces in `buildExtrudedCell`. The cloud hook retains those exact decisions, including the interior faces needed while flying through clouds; its additional saving is the direction-array allocation. It does not remove required interior geometry or alter cloud alpha.

## Files and validation

Pure core sources: `OpaqueLayerSweep.java`, `DirectMappedSweepCache.java`, `LeafCullingPolicy.java`, `ShadowGroundCacheCore.java` in `core/src/main/java/com/ephemera/core/culling/`. None imports Minecraft, Fabric, LWJGL or Mixin.

Tests in the corresponding core test package: `OpaqueLayerSweepTest`, `DirectMappedSweepCacheTest`, `ShadowGroundCacheCoreTest`, `LeafCullingPolicyTest`, plus `CullingCoreTestSuite` for running the same JUnit tests on the lightweight worker classpath. The 24 tests cover complete walls versus open sight lines, partial walls, all axes, negative coordinates, expanded cameras, bounded work, invalid inputs, 1,000 randomized punched-wall cases, all cache-key fields, TTL boundaries, collisions, invalidation and every leaf mode.

Client helpers in `com.ephemera.render.culling`: `EphemeraVisibilityService`, `EntityOcclusionManager`, `FastShadowCache`, `ParticleCullingManager`, `BlockStateCullingManager`, and the type-field interface `RendererTypeCache`. The complete list of 27 mixin/accessor files is in the registration table above.

Verification used the prescribed worker check (PowerShell array arguments passed directly so both core directories are compiled):

```powershell
& tools/check_worker.ps1 culling-entities `
  -CoreDirs core/src/main/java/com/ephemera/core/culling,core/src/test/java/com/ephemera/core/culling `
  -ClientDirs src/client/java/com/ephemera/render/culling,src/client/java/com/ephemera/mixin/client/culling,src/client/java/com/ephemera/mixin/client/particle
```

Result: `CHECK OK (culling-entities)` (9 core/test and 33 client files). The 24 JUnit tests passed. A class-file contract audit matched all 40 selectors plus shadow fields, accessor/invoker descriptors and invocation targets against the 26.2 jar. An isolated Fabric Knot probe with default injection requirements and bytecode verification applied all 27 mixins/accessors to all 22 target classes and completed `MixinEnvironment.audit()` successfully. It only loaded/transformed classes; it did not launch the game, Gradle or `runClient`. GPU visual acceptance and measured FPS/allocation gains remain integration validation work; no new benchmark claim is made.
