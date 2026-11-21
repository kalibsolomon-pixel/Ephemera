# Memory, lighting, game logic, loading and networking

This module targets Minecraft 26.2, Java 25 and Fabric Loader 0.20.1. Registration belongs to the shared mixin manifests and plugins. Use the simple class name as the plugin key, including for names with the client `engine.` prefix. The plugin applies `yieldsTo` when `modStandDownEnabled` is enabled. There are no static `init()` hooks. State pools initialize on first use and the light and render caches belong to their game objects.

## Common registration

Names are relative to `com.ephemera.mixin.common`. Use the same combined config predicate for both block-state mixins.

| Mixin | Config | yieldsTo | Target and behavior |
| --- | --- | --- | --- |
| `BlockStateCacheMixin` | `memoryCompactBlockStates \|\| memoryDeduplicateShapes` | `ferritecore` | Interns completed face-support arrays and collision shapes in the cache constructor. Supplies the cache comparison key. |
| `BlockStateMixin` | `memoryCompactBlockStates \|\| memoryDeduplicateShapes` | `ferritecore` | Shares completed state caches, occlusion shapes and immutable per-face arrays in `BlockStateBase.initCache`. |
| `BlockShapeMixin` | `memoryDeduplicateShapes` | `ferritecore`, `lithium` | Answers `Block.isShapeFullBlock` directly for the empty and full-block singletons. |
| `CompactTrigMixin` | `engineFastMath` | `lithium` | Samples the 4096-float sine table in `Mth.sin(double)` and `cos(double)`. |
| `ChunkModuloMixin` | `engineFastChunkLookup` | `lithium` | Masks positive power-of-two divisors in integer `Mth.positiveModulo`. Other divisors retain Java floor-mod semantics. |
| `LevelChunkIndexMixin` | `engineFastChunkLookup` | `lithium` | Caches the minimum section of concrete vanilla level chunks for block/fluid reads and block writes. Subclasses use their virtual index method. |
| `PaletteIndexAccessor` | `engineFastChunkLookup` | `lithium` | Invokes the protected linear-index getter on `PalettedContainer`. |
| `SectionStateMixin` | `engineFastChunkLookup` | `lithium` | Packs local block coordinates before reading the section's block-state palette for state and fluid queries. |
| `LightChunkCacheMixin` | `engineFastLighting` | `scalablelux`, `lithium` | Expands both arrays and the lookup window in `LightEngine.getChunk` to eight slots. |
| `LightSectionCacheMixin` | `engineFastLighting` | `scalablelux`, `lithium` | Expands both arrays, lookup and invalidation in `DataLayerStorageMap`, the map owned by `LayerLightSectionStorage`. |
| `LightWorkMixin` | `engineFastLighting` | `scalablelux`, `lithium` | Stores increase/decrease work in separate flat rings and raises the node set's post-pass trim target from 512 to 2048. |
| `DataLayerMixin` | `engineFastLighting` | `scalablelux`, `lithium` | Reads uniform layers directly and extracts packed nibbles from materialized layers. |
| `ConnectionFlushMixin` | `networkFlushConsolidation` | `krypton` | Installs one Netty flush consolidator per pipeline. Flushes can coalesce during reads and within an event-loop turn, with a 256-request flush threshold. |

## Client registration

Names are relative to `com.ephemera.mixin.client`.

| Mixin | Config | yieldsTo | Target and behavior |
| --- | --- | --- | --- |
| `engine.SpriteContentsMixin` | `memoryFreeSpriteMipmaps` | `ferritecore` | Releases upper native images in vanilla sprite contents and regenerates them on a subsequent upload or animation-state rebuild. |
| `engine.TextureAtlasMipMixin` | `memoryFreeSpriteMipmaps` | `ferritecore` | Calls the release hook after a successful return from the atlas upload. |
| `engine.LightmapMixin` | `engineLightmapCaching` | `badoptimizations` | Compares all six scalar and twelve vector-component uniform bits per lightmap before repeating an update. Records inputs after a successful draw. |
| `engine.LightmapShaderReloadMixin` | `engineLightmapCaching` | `badoptimizations` | Invalidates sampled inputs after the shader manager applies new configs. |
| `engine.DebugEntryMixin` | `engineDebugHudCache` | `badoptimizations` | Replays debug text for up to 50 ms. World, chunk and enabled-entry changes invalidate the cache. Charts and layout still execute each frame. |
| `engine.SoundListenerMixin` | `engineSoundListenerThrottle` | `badoptimizations` | Compares position, forward and up before allocating or enqueueing a listener transform. Invalidates on library load, stop-all and teardown. |
| `engine.ToastIdleMixin` | `engineIdleShortCircuits` | `badoptimizations` | Skips empty toast update and extraction while preserving music-toast updates and end-of-update sound cleanup. |
| `engine.TutorialIdleMixin` | `engineIdleShortCircuits` | `badoptimizations` | Skips the completed vanilla step in a loaded, non-demo world. |
| `engine.DebugRendererIdleMixin` | `engineIdleShortCircuits` | `badoptimizations` | Skips gizmo extraction when the renderer list is empty and the debug-entry version is current. |
| `engine.FovIdleMixin` | `engineIdleShortCircuits` | `badoptimizations` | Returns the neutral FOV multiplier for zero effect scale. First-person scope zoom follows the game path. |
| `engine.LevelLoadDelayMixin` | `loadingSkipLevelLoadDelay` | `rrls` | Removes the 500 ms delay after the integrated world's client state reaches ready. |
| `engine.TitleFadeMixin` | `loadingSkipTitleFade` | `rrls` | Clears the title-screen constructor's cosmetic fading flag. |
| `engine.LoadingFadeMixin` | `loadingNonBlockingReload` | `rrls` | Shortens the post-completion overlay fade divisor to 75 ms. Reload application, error reporting and completion callbacks run normally. |
| `engine.NumericAddressMixin` | `loadingFastIpPing` | `fast-ip-ping` | Skips the resolved-address hostname lookup when the vanilla address check has zero predicates. Installed filters still receive the hostname and IP, including reverse DNS when needed. |

## Implementation details

- The pure Java sources are `FastMath` and `FastLightQueue`, with corresponding JUnit classes `FastMathTest` and `FastLightQueueTest`. Trig interpolation preserves the game's 16-bit phase quantization. The hot table occupies 16 KiB. Vanilla's private 256 KiB table stays available for the disabled path and other code using it.
- The common helpers are `StateInterning`, `StateCacheKey` and `StateCacheView`. Each interning table has 2048 slots. Sharing requires matching geometry, axis coordinates and concrete vanilla shape type, or matching canonical field identities and flags for whole caches. Collisions evict a candidate. State support and face arrays are shared only after construction. In 26.2, visual shapes are context-dependent method results. Empty component patches already use `DataComponentPatch.EMPTY` in the builder and codec.
- Level chunks already mask local coordinates, and `Strategy.getIndex` uses shifts. The section mixin specializes the fixed 16³ block palette. Biome palettes retain their own strategy. Lighting uses the game's packed block positions and mutable scratch position, preserves both 64-bit words of each work item, and retains vanilla storage publication and invalidation. Each engine selects its queue implementation at construction so pending work stays in one FIFO.
- The client helpers are `ReleasableSprite`, `LightmapInputs` and `DebugEntryText`. Mip level zero remains available for transparency and later mip generation. Vulkan stages the pixel data in `VulkanCommandEncoder.writeToTexture`. Animated sprites build their GPU animation state before the release hook. The loading mixins operate on presentation timing only.

## Verification

The worker compiler compiled 37 Java files and reported `CHECK OK (engine-mem-light)`. All six JUnit tests passed. Isolated Fabric Knot probes applied the mixins to 25 target classes on the 26.2 client jar and the 11 common targets on the extracted dedicated-server jar. Both environments bootstrapped the block registry. The 32,167 states with a cache used approximately 6,500 distinct cache objects in each probe.

In both environments, block and sky light matched the disabled path at 221,184 sampled positions across three waves with light addition, opacity changes and removal spanning positive and negative chunk and section boundaries. The probes verified every 16-bit trig phase with the mixin enabled and disabled. GPU rendering, FPS changes and live network latency require separate integration measurements.
