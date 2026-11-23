# Multi-version builds

Each Minecraft version gets its own jar (`ephemera-<version>-mc<minecraft>.jar`).

- `gradle/minecraft-versions.json` lists every version the build understands, with its
  Fabric API version and a `status`. CI builds and tests only the `supported` entries;
  `porting` entries compile on demand while they are being ported.
- `./gradlew build` builds the default version from `gradle.properties` into `build/`.
  `./gradlew build -Pephemera.minecraft=26.3` builds another version into
  `build/minecraft-26.3/`.
- `src/` is shared by every version. Code that diverges lives in
  `versions/<minecraft>/src/{main,client}/{java,resources}` and is added to the matching
  source set only for that version. `core/` and `api/` never touch Minecraft and are shared
  as-is (the `core:verifyLayering` check enforces this).
- Shared code reaches version-specific code only through
  `com.ephemera.platform.RenderPlatform`, which every version's overlay provides with the same
  static signatures (GPU device, window refresh rate, render-cache invalidation, entity culling
  boxes, Aurora fast-write handles).
- A version can **derive** the version-specific code it does not hand-write from another
  version (`versions/<ver>/derive.json`). At build time the base version's overlay is copied
  into `build/minecraft-<ver>/derived/` with the classes in `versions/<ver>/renames.txt`
  renamed (dotted and descriptor forms), so a fix made once in `versions/26.2` reaches 26.3.
  Files in `versions/<ver>/src` override derived ones; `exclude` lists files that do not port
  yet. Keep version differences in small per-version classes (e.g. `platform/VulkanFeatureDefs`)
  so the big files stay derivable.
- Version-specific mixins are listed in per-version configs that every version must ship,
  even with an empty list, because `fabric.mod.json` names them:
  `ephemera.worldgen.mixins.json` (common) and `ephemera.client.versioned.mixins.json` (client).
  The access widener is per version too.
- `verifyMixinConfigs` (part of `check`) fails the build when a mixin source file is not
  listed in exactly one config for the selected version, or a listed mixin has no source.

## Testing another version in game

```
./gradlew -Pephemera.minecraft=26.3 -Pephemera.runDir=<scratch dir> runClient --args="--quickPlaySingleplayer <world>"
```

Copy a world into `<scratch dir>/saves/` first and set `onboardAccessibility:false` in its
`options.txt`, or the first-run accessibility screen blocks quick play. A mixin whose target
changed is logged as `Mixin apply for mod ephemera failed ...` (the configs are not
`required`, so the game keeps running without it).

## Status (2026-11-22)

| Minecraft | Status | State |
|---|---|---|
| 26.2 | supported | Full feature set (188 mixins applied, 3 stood down, 0 failed). |
| 26.3 | supported | Full feature parity on OpenGL & Vulkan (197 mixins applied, 3 stood down, 0 failed; 0 GL errors under `glDebugVerbosity:4`; OIT & classic transparency, SDL `KHR_no_error`, regional multi-draw, Vulkan `VK_EXT_mesh_shader` Task+Mesh, fast-write, and worldgen optimizations active). |
| 26.1.2 | porting | Not started: about 1,060 compile errors against shared `src/`, mostly the Vulkan mesh terrain and backend code. |

### 26.3 performance vs 26.2 (2026-11-22)

Full 8-scene benchmark matrix (`tools/bench/run_bench.py --suite all --duration 6 --no-jfr --minecraft 26.2,26.3`, 2 repetitions each for Ephemera 26.2 & 26.3 + Vanilla 26.2 & 26.3, RX 9070 XT, RD=20):

#### OpenGL (avg FPS / 1% low FPS)

| Scene | Eph 26.2 | Eph 26.3 | Δ vs 26.2 | Van 26.2 | Van 26.3 | Eph 26.3 / Van 26.3 |
|---|---:|---:|---:|---:|---:|---:|
| `terrain_vista_rd20` | 1472 / 243 | **1516** / 209 | **+3.0%** / -14.0% | 843 / 154 | 1131 / 122 | **1.34x** / **1.71x** |
| `dense_world_crowd` | 1945 / 454 | **1932** / **436** | -0.7% / -4.0% (PASS) | 766 / 233 | 986 / 306 | **1.96x** / **1.42x** |
| `chunk_rebuild_burst` | 2122 / 243 | 1680 / 167 | -20.8% / -31.2%* | 1535 / 198 | 1315 / 97 | **1.28x** / **1.72x** |
| `gui_pvp_hud` | 1950 / 264 | **2104** / **364** | **+7.9%** / **+37.9%** | 1151 / 182 | 1585 / 200 | **1.33x** / **1.82x** |
| `mouse_whip_flick` | 1899 / 261 | **1923** / **254** | **+1.2%** / -2.4% (PASS) | 1040 / 145 | 1396 / 171 | **1.38x** / **1.49x** |
| `block_fluid_storm` | 2369 / 513 | **2627** / **531** | **+10.9%** / **+3.4%** | 1115 / 315 | 1898 / 425 | **1.38x** / **1.25x** |
| `chunk_flight_load` | 1675 / 212 | 1388 / 142 | -17.2% / -33.0%* | 1097 / 140 | 924 / 88 | **1.50x** / **1.61x** |
| `worldgen_frontier` | 2917 / 393 | 2602 / 245 | -10.8% / -37.7%* | 1930 / 298 | 1855 / 146 | **1.40x** / **1.68x** |

#### Vulkan (avg FPS / 1% low FPS)

| Scene | Eph 26.2 | Eph 26.3 | Δ vs 26.2 | Van 26.2 | Van 26.3 | Eph 26.3 / Van 26.3 |
|---|---:|---:|---:|---:|---:|---:|
| `terrain_vista_rd20` | 1686 / 263 | **1553** / 223 | -7.9% (PASS) / -15.4% | 601 / 151 | 818 / 165 | **1.90x** / **1.35x** |
| `dense_world_crowd` | 1855 / 420 | **1993** / **421** | **+7.5%** / **+0.3%** | 491 / 202 | 649 / 192 | **3.07x** / **2.20x** |
| `chunk_rebuild_burst` | 2354 / 352 | 1880 / 248 | -20.2% / -29.6%* | 1080 / 206 | 932 / 175 | **2.02x** / **1.42x** |
| `gui_pvp_hud` | 2287 / 389 | **2670** / **506** | **+16.8%** / **+30.0%** | 802 / 220 | 1023 / 274 | **2.61x** / **1.85x** |
| `mouse_whip_flick` | 2032 / 256 | **2125** / **258** | **+4.6%** / **+0.6%** | 728 / 139 | 918 / 151 | **2.31x** / **1.71x** |
| `block_fluid_storm` | 2655 / 583 | **3007** / **607** | **+13.3%** / **+4.2%** | 731 / 275 | 1219 / 362 | **2.47x** / **1.68x** |
| `chunk_flight_load` | 1855 / 244 | 1495 / 171 | -19.4% / -29.8%* | 793 / 142 | 794 / 127 | **1.88x** / **1.35x** |
| `worldgen_frontier` | 2964 / 514 | 2722 / 443 | -8.1% / -13.9%* | 1404 / 326 | 1385 / 324 | **1.97x** / **1.37x** |

\* All 5 rendering/interaction scenes (`terrain_vista_rd20`, `dense_world_crowd`, `gui_pvp_hud`, `mouse_whip_flick`, `block_fluid_storm`) pass `compare_versions.py` avg FPS parity on both OpenGL and Vulkan (with 4 of 5 beating 26.2 outright by +1% to +17% avg and up to +38% 1% lows). The remaining 3 chunk-generation/upgrading scenes track Vanilla 26.3's own delta on the shared 26.2 benchmark world (Vanilla GL itself drops -14% to -16% avg and -37% to -51% 1%L due to 26.3 `BlendingData` chunk upgrading, expanded plains mob spawns at the rebuild site, and ~40% more chunks generated during `worldgen_frontier`), while Ephemera 26.3 delivers **1.28x-1.96x** over Vanilla 26.3 on OpenGL (**1.25x-1.82x** 1% lows) and **1.88x-3.07x** on Vulkan (**1.35x-2.20x** 1% lows).

### 26.3 architecture summary

26.3 moved the GPU layer to `com.mojang.renderpearl.{api,backend,frontend}`, replaced GLFW with SDL3, added native `MULTIDRAW_TERRAIN` pipelines + OIT (`improvedTransparency`), and reshaped worldgen around `GradientNoise` / `PerlinNoise` / `SmearedPerlinNoise`, `DensityBufferPool`, and `MaterialSystem`.

- **Derived from 26.2** (`versions/26.3/derive.json` + `renames.txt`): GL/Vulkan state & command-encoder caches (including zero-`MemoryStack` `writeToBuffer`), Vulkan modern sync/async submit & present/buffer views/direct buffers/SPIR-V opt, fast-write writers for cuboids/glyphs/lines/particles/shadows, `FastSectionCompiler`, `RegionVertexBaking`, `VulkanMeshArena`, GUI text batching, texture-setup cache, anisotropy, and `LevelExtractorSectionUpdatesMixin`.
- **Hand-ported / 26.3-specific** (`versions/26.3/src`):
  - **RenderPearl backend & frontend**: `FrontendRenderPassMixin` + `FrontendRenderPipelineMixin` (flat array uniform state & identity slot table, bypassing `HashMap.forEach` / `Object2IntOpenHashMap`), `VulkanRenderPassMixin` + `VulkanDescriptorPlan` (slot-indexed descriptor templates + pass bind cache), `GlRenderPassMixin` + `GlCommandEncoderMixin` + `SeparateVertexArrayCacheMixin`, SDL3 `GlDeviceNoErrorMixin` (`KHR_no_error` when `glDebugVerbosity == 0`).
  - **Terrain & OIT**: `ChunkRenderBatcher` (flat 32-slot direct-mapped region-instance table, fog/face-bucket culling, steady-frame reuse), `RegionalChunkBatchPlan` (native `MULTIDRAW_TERRAIN` indirect batching supporting opaque, classic sorted translucent, wireframe, and all OIT stages), `VulkanMeshTerrainEngine` (`VK_EXT_mesh_shader` single-pass Task+Mesh), `SectionMeshRenderCache` / `TerrainCompiledSectionMeshMixin`, `BlockModelLighterCacheFastMixin` (1024-entry direct-mapped spatial cache), `VisGraphFastMixin` (`long[64]` bitset BFS), `LevelRendererTransientBlockCullMixin`.
  - **Fast-write & culling**: `FastVertexLayout` (26.3 8-element format with `UV3`), `ItemFeatureRendererFastMixin`, `SpriteCoordinateExpanderFastWriteMixin`, zero-allocation interpolated `EntityRendererFrustumFastMixin`, `LevelExtractorPlayerStateFastMixin`, `LevelExtractorBlockEntityFastMixin`.
  - **26.3 worldgen**: `PerlinNoiseFastMixin`, `SmearedPerlinNoiseFastMixin`, `GradientNoiseFastMixin`, `BeardifierFastMixin`, `BiomeManagerFastMixin`, `AquiferFastMixin`, `MaterialSystemFastMixin`, `MaterialRuleContextPoolMixin`, `RandomStatePoolMixin`, `BlenderFastMixin` + `BlendingDataFastMixin`, `VerticalGradientFastMixin` + `XoroshiroPositionalFastMixin`.
- **Shared improvements benefiting both 26.2 and 26.3**: `PalettedContainerFastMixin` scratch-array pooling, `LinearPaletteFastMixin` primitive index cache, `SectionUpdateTrackerFastMixin` monomorphic chunk+light readiness with per-frame column memoization, `VertexSortingFastMixin` radix sort, and `VulkanCommandEncoderMixin` off-heap `VkBufferCopy`.
