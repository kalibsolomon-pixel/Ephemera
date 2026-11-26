<p align="center">
  <img src="docs/assets/ephemera-logo.svg" alt="Ephemera Logo" width="116" height="116" />
</p>

<h1 align="center">Ephemera</h1>

<p align="center">
  <strong>Clean-room, MIT-licensed full-stack Minecraft performance engine for Fabric</strong><br />
  Native <strong>Vulkan (<code>VK_EXT_mesh_shader</code>)</strong> &amp; <strong>OpenGL 3.3 Core</strong> &middot; Minecraft <strong>26.2 &amp; 26.3</strong> &middot; Java 25 &middot; Client + Dedicated Server (<code>environment: "*"</code>)
</p>

<p align="center">
  <a href="https://github.com/kalibsolomon-pixel/Ephemera/releases/latest"><img src="https://img.shields.io/github/v/release/kalibsolomon-pixel/Ephemera?style=flat-square&color=2dd4bf&label=Release" alt="Latest Release" /></a>
  <img src="https://img.shields.io/badge/Minecraft-26.2%20%7C%2026.3-3b82f6?style=flat-square" alt="Minecraft 26.2 and 26.3" />
  <img src="https://img.shields.io/badge/Loader-Fabric%200.20+-a78bfa?style=flat-square" alt="Fabric 0.20+" />
  <img src="https://img.shields.io/badge/Java-25+-f59e0b?style=flat-square" alt="Java 25+" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-34d399?style=flat-square" alt="MIT License" /></a>
</p>

---

**Ephemera** is a standalone, single-JAR Fabric performance engine that accelerates Minecraft from chunk generation and memory storage all the way through culling, vertex emission, GUI batching, frame pacing, and modern GPU terrain submission on **both Native Vulkan and OpenGL**. It also ships as the default managed performance engine in [Aurora Launcher](https://github.com/kalibsolomon-pixel/Aurora-Launcher) alongside [Aurora Client](https://github.com/kalibsolomon-pixel/Aurora-Client).

## Why Ephemera?

| Advantage | What It Delivers |
|---|---|
| **One Mod, Full Stack** | Combines terrain rendering, GPU dispatch caching, native vertex fast-write, entity/block-entity/particle/block-state culling, GUI &amp; text batching, memory deduplication, fast lighting, worldgen noise/biome math, Netty flush consolidation, and frame pacing in one ~880 KB JAR (`environment: "*"` for both client and dedicated servers). |
| **Native Vulkan Faster Than OpenGL** | On modern AMD (RDNA 2/3/4) and NVIDIA (RTX 20–50) GPUs, Ephemera's cross-vendor `VK_EXT_mesh_shader` single-pass **Task + Mesh** pipeline with ReBAR ring buffers, strict 4-image swapchain widening, and asynchronous queue presentation delivers **3.31&times; Vanilla** and **2.49&times; Adrenaline (Sodium modpack)** geomean FPS — turning Vulkan into the fastest backend (+9.6% avg / +37.6% 1% lows over OpenGL). |
| **First-Class OpenGL 3.3 Core** | Single `glMultiDrawElementsIndirect` per vertex heap via `gl_BaseInstanceARB` (with automatic fallback to direct `glMultiDrawElementsBaseVertex` on macOS GL 4.1 / older drivers), pass-scoped GL state/VAO/EAB/FBO/UBO caches, and scoped `KHR_no_error` contexts deliver **1.95&times; Vanilla** and **1.59&times; Adrenaline** geomean FPS. |
| **Smooth Frame Pacing &amp; 1% Lows** | A unified per-frame chunk-streaming budget, staging-arena watermark drain, GPU-heap retention hysteresis, worker park backoff, anti-sawtooth adaptive frame pacer, exact-flip borderless fullscreen, and Win32 HighQoS P-core scheduling boost **1% Low FPS by +100% to +179%** over Vanilla and **+45% to +144%** over Adrenaline while cutting heap allocations by **&minus;56% to &minus;59%**. |
| **Zero-Overhead Visual Polish &amp; Modern UI** | Built-in smooth fog, interleaved gradient dithering, enhanced AO, anisotropic mipmaps, smooth chunk fade, zero-rebuild dynamic lights, and optional Held World Light — all automatically standing down when an Iris/Oculus shader pack is active — paired with a full-page Video Settings screen and live diagnostics. |
| **Fail-Soft &amp; Clean-Room MIT** | Per-mixin automatic stand-downs let Ephemera coexist cleanly with specialized mods (`Sodium`, `Iris`, `Lithium`, `C2ME`, `ImmediatelyFast`, `EntityCulling`, `MoreCulling`, `FerriteCore`, `ScalableLux`, `Krypton`, etc.). Pure Java 25 algorithms live in `:core` with zero Minecraft/Fabric/LWJGL dependencies. |

---

## Performance Benchmarks

All benchmarks below were measured **uninstrumented (`--no-jfr`)** on a quiet system across Ephemera's standardized **10-workload suite** sampled over **30 seconds per workload** (8 workloads at Render Distance 20 + 2 workloads at Render Distance 32):

- **Test System**: AMD Radeon RX 9070 XT (driver 26.11.2) &middot; Intel Core i7-14700F &middot; 1080&times;720 &middot; Minecraft 26.2 &middot; Fabric Loader 0.20.1 &middot; Java 25
- **Configs Tested**: Vanilla 26.2 &middot; Adrenaline `26.5.1` (27-mod Sodium 0.9.2 performance modpack) &middot; Vulkan Optimization `26.2-6.2` (25-mod native Vulkan pack) &middot; **Ephemera `1.4.0`**

### 10-Workload Geometric Mean Summary (30 s / workload)

| Backend | Metric | Vanilla 26.2 | Adrenaline (Sodium) | Vulkan Opt | **Ephemera 1.4.0** | vs. Vanilla | vs. Adrenaline |
|---|---|---:|---:|---:|---:|---:|---:|
| **Vulkan** | **Avg FPS** *(higher is better)* | 862.4 | 1,146.6 | 1,142.8 | **2,855.3** | **+231.1% (3.31&times;)** | **+149.0% (2.49&times;)** |
| **Vulkan** | **1% Low FPS** *(higher is better)* | 255.0 | 292.1 | 275.6 | **712.3** | **+179.3% (2.79&times;)** | **+143.9% (2.44&times;)** |
| **Vulkan** | **p99 Frame Time** *(lower is better)* | 2.44 ms | 2.08 ms | 2.18 ms | **0.89 ms** | **&minus;63.5%** | **&minus;57.2%** |
| **Vulkan** | **Heap Allocation** *(lower is better)* | 433.2 MB/s | 288.3 MB/s | 269.1 MB/s | **191.1 MB/s** | **&minus;55.9%** | **&minus;33.7%** |
| **OpenGL** | **Avg FPS** *(higher is better)* | 1,334.3 | 1,642.9 | 1,653.2 | **2,605.9** | **+95.3% (1.95&times;)** | **+58.6% (1.59&times;)** |
| **OpenGL** | **1% Low FPS** *(higher is better)* | 259.0 | 357.2 | 326.6 | **517.6** | **+99.8% (2.00&times;)** | **+44.9% (1.45&times;)** |
| **OpenGL** | **p99 Frame Time** *(lower is better)* | 2.18 ms | 1.69 ms | 1.73 ms | **1.08 ms** | **&minus;50.5%** | **&minus;36.1%** |
| **OpenGL** | **Heap Allocation** *(lower is better)* | 537.4 MB/s | 417.7 MB/s | 402.2 MB/s | **220.1 MB/s** | **&minus;59.0%** | **&minus;47.3%** |

<details>
<summary><strong>Full 10-Workload Raw Data (30 s / workload) &amp; Congested Multiplayer Hub Results</strong></summary>

#### Native Vulkan (MC 26.2, 30 s/workload, No JFR)

| Workload | Vanilla FPS / 1%L | Adrenaline FPS / 1%L | **Ephemera 1.4.0 FPS / 1%L** | p99 (Van / Adr / **Eph**) | Alloc MB/s (Van / Adr / **Eph**) |
|---|---:|---:|---:|---:|---:|
| `terrain_vista_rd20` | 790.5 / 246.1 | 1,234.4 / 348.5 | **2,670.3 / 677.6** | 2.68 / 1.88 / **0.97 ms** | 457.3 / 219.1 / **158.2** |
| `terrain_vista_rd32` | 682.5 / 163.3 | 1,071.6 / 219.7 | **2,439.8 / 543.6** | 3.32 / 2.74 / **1.08 ms** | 383.5 / 269.9 / **141.8** |
| `dense_world_crowd` | 563.7 / 262.6 | 1,245.8 / 465.0 | **2,444.9 / 771.8** | 2.90 / 1.35 / **0.84 ms** | 576.3 / 404.1 / **305.8** |
| `chunk_rebuild_burst` | 943.7 / 238.7 | 1,187.4 / 212.7 | **2,978.6 / 656.3** | 2.24 / 2.71 / **0.88 ms** | 372.9 / 242.5 / **161.3** |
| `chunk_rebuild_burst_rd32` | 854.7 / 220.3 | 1,175.4 / 189.0 | **2,846.2 / 623.5** | 2.31 / 2.68 / **0.95 ms** | 394.3 / 264.5 / **172.7** |
| `gui_pvp_hud` | 944.1 / 384.3 | 1,286.8 / 498.8 | **3,387.2 / 980.1** | 1.87 / 1.30 / **0.69 ms** | 464.9 / 336.5 / **296.6** |
| `mouse_whip_flick` | 835.7 / 223.6 | 1,317.5 / 395.6 | **2,869.1 / 733.9** | 2.79 / 1.45 / **0.87 ms** | 422.5 / 241.9 / **235.2** |
| `block_fluid_storm` | 755.6 / 310.3 | 1,525.5 / 636.4 | **2,921.9 / 956.8** | 2.35 / 1.15 / **0.78 ms** | 646.4 / 336.4 / **164.4** |
| `chunk_flight_load` | 1,245.2 / 235.5 | 574.1 / 95.2 | **2,992.9 / 552.7** | 2.59 / 6.27 / **1.02 ms** | 354.9 / 299.2 / **166.4** |
| `worldgen_frontier` | 1,248.8 / 334.2 | 1,150.3 / 264.8 | **3,139.1 / 760.3** | 1.79 / 2.21 / **0.84 ms** | 347.9 / 316.2 / **178.2** |
| **Geomean (10 scenes)** | **862.4 / 255.0** | **1,146.6 / 292.1** | **2,855.3 / 712.3** | **2.44 / 2.08 / 0.89 ms** | **433.2 / 288.3 / 191.1** |

#### OpenGL 3.3 Core (MC 26.2, 30 s/workload, No JFR)

| Workload | Vanilla FPS / 1%L | Adrenaline FPS / 1%L | **Ephemera 1.4.0 FPS / 1%L** | p99 (Van / Adr / **Eph**) | Alloc MB/s (Van / Adr / **Eph**) |
|---|---:|---:|---:|---:|---:|
| `terrain_vista_rd20` | 1,248.8 / 273.0 | 1,686.1 / 451.7 | **2,648.4 / 593.8** | 2.22 / 1.49 / **1.08 ms** | 495.4 / 327.8 / **201.0** |
| `terrain_vista_rd32` | 997.3 / 130.3 | 1,507.6 / 269.4 | **2,028.2 / 320.5** | 3.90 / 2.18 / **1.39 ms** | 495.3 / 281.4 / **156.0** |
| `dense_world_crowd` | 868.4 / 324.2 | 1,818.9 / 547.9 | **2,325.2 / 728.4** | 2.23 / 1.15 / **0.99 ms** | 705.3 / 630.9 / **337.0** |
| `chunk_rebuild_burst` | 1,427.8 / 208.7 | 1,832.6 / 298.4 | **2,600.2 / 409.0** | 2.08 / 1.84 / **1.15 ms** | 443.8 / 404.0 / **194.9** |
| `chunk_rebuild_burst_rd32` | 1,272.7 / 170.0 | 1,820.7 / 260.4 | **2,481.7 / 340.0** | 2.51 / 1.94 / **1.17 ms** | 564.2 / 394.9 / **179.5** |
| `gui_pvp_hud` | 1,559.1 / 507.3 | 1,996.6 / 624.1 | **2,947.1 / 862.2** | 1.49 / 1.08 / **0.82 ms** | 604.3 / 569.7 / **346.1** |
| `mouse_whip_flick` | 1,406.1 / 289.6 | 1,999.1 / 505.7 | **2,509.2 / 611.7** | 2.07 / 1.18 / **1.00 ms** | 506.1 / 396.5 / **250.0** |
| `block_fluid_storm` | 1,288.5 / 433.0 | 1,959.4 / 629.0 | **3,088.8 / 920.1** | 1.76 / 1.08 / **0.83 ms** | 725.8 / 487.3 / **222.1** |
| `chunk_flight_load` | 1,801.2 / 205.6 | 758.1 / 107.6 | **2,689.4 / 337.5** | 2.44 / 5.56 / **1.37 ms** | 475.1 / 361.0 / **187.5** |
| `worldgen_frontier` | 1,788.6 / 253.6 | 1,565.8 / 305.0 | **2,917.0 / 437.5** | 1.78 / 1.85 / **1.11 ms** | 439.9 / 438.4 / **200.1** |
| **Geomean (10 scenes)** | **1,334.3 / 259.0** | **1,642.9 / 357.2** | **2,605.9 / 517.6** | **2.18 / 1.69 / 1.08 ms** | **537.4 / 417.7 / 220.1** |

#### Congested Multiplayer Hub (Dedicated Server TCP, 2560&times;1440, Borderless)

Tested against a live dedicated server (`compression=256`) with 120 TextDisplays, 64 ArmorStands, 80 mobs, 80 ItemDisplays, 40 player skulls, live scoreboard/bossbar/chat/particle packets:

| Scenario | Vulkan (Vanilla &rarr; **Ephemera**) | OpenGL (Vanilla &rarr; **Ephemera**) |
|---|---:|---:|
| **Crowded Hub Plaza** | 725.5 &rarr; **1,453.8 FPS** (**2.00&times;**) &middot; &minus;52.7% alloc | 886.8 &rarr; **1,469.4 FPS** (**1.66&times;**) &middot; &minus;68.8% alloc |
| **Occluded Market Hall** | 847.5 &rarr; **1,674.0 FPS** (**1.98&times;**) &middot; &minus;54.2% alloc | 1,059.7 &rarr; **1,578.7 FPS** (**1.49&times;**) &middot; &minus;71.9% alloc |
| **Congested PvP Whip** | 609.7 &rarr; **1,410.7 FPS** (**2.31&times;**) &middot; &minus;50.0% alloc | 820.2 &rarr; **1,282.3 FPS** (**1.56&times;**) &middot; &minus;70.2% alloc |

</details>

---

## How Ephemera Works

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/architecture-dark.svg" />
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/architecture-light.svg" />
    <img src="docs/assets/architecture-dark.svg" alt="Ephemera Engine Architecture Diagram" width="100%" />
  </picture>
</p>

### 1. Dual-Backend Terrain Engine

Ephemera accelerates terrain compilation off-thread and submits visible geometry through specialized hardware paths on both backends while keeping full compatibility with Minecraft's block models, translucent sorting, and resource packs:

- **Native Vulkan (`VK_EXT_mesh_shader` Single-Pass Task + Mesh)** — On modern AMD (RDNA 2/3/4) and NVIDIA (Turing/Ampere/Ada/Blackwell) GPUs:
  - Off-thread `MeshletBaker` packs vanilla 28-byte `BLOCK` quads into **16-byte `uvec4` vertices** (64 bytes/quad, zero index buffer) grouped into **32-quad hardware meshlets** (128 vertices, 64 triangles — matching one AMD Wave32 / NVIDIA Warp32) with quantized 8-bit local AABBs.
  - A single hardware **Task Shader** (`local_size_x = 32`) evaluates section fog occlusion, 7-bucket directional face culling, and per-meshlet AABB frustum culling on-chip, precomputing `mvp` once per section and passing surviving meshlets directly via `taskPayloadSharedEXT` — **zero compute pass, zero compute-to-graphics barrier, zero `visibleMeshlets` VRAM traffic**, and one `vkCmdDrawMeshTasksEXT` per opaque layer.
  - Static pools reside in device-local VRAM via VMA (`quadBuf`, `meshletBuf`, `sectionMetaBuf`), while per-frame buffers (`visibleTasksRing`, `cullParamsRing`, `meshFrameRing`) use **3-frame host-visible mapped ReBAR/SAM rings** for zero-copy CPU writes without steady-state `vkCmdUpdateBuffer` barriers.
  - **Atomic Same-Frame Fallback**: Both `SOLID` and `CUTOUT` pipelines are pre-warmed off-pass and verified before frame handoff; any shader/pipeline failure or external shader pack transparently falls back to indexed multi-draw on the exact same frame with zero missing terrain.
  - **Asynchronous Vulkan Queue Present (`VulkanAsyncPresenter`)**: Moves blocking WDDM/DWM kernel transitions (`vkQueueSubmit2KHR` ~70–130 &mu;s and `vkQueuePresentKHR` ~250 &mu;s) onto a dedicated FIFO worker thread through a lock-free SPSC ring buffer while preserving strict Vulkan external synchronization.
- **OpenGL 3.3 Core (`GlSingleMdiTerrain` &amp; Regional Multi-Draw)**:
  - Collapses every solid and cutout draw on a vertex heap into **one `glMultiDrawElementsIndirect`** regardless of region or fading state, reading per-draw `(origin, visibility)` via `gl_BaseInstanceARB` from a 4-buffer persistent coherently mapped `EphemeraTerrainBatch` UBO ring.
  - On devices without `ARB_shader_draw_parameters` / `ARB_buffer_storage` (e.g. macOS GL 4.1 or older Intel iGPUs) or custom resource-pack terrain shaders, seamlessly falls back to regional direct `glMultiDrawElementsBaseVertex` (zero indirect buffer upload) or per-section draws.
- **Shared Terrain Compiler &amp; Planner Optimizations**:
  - **Symmetric 7-Bucket Directional Face Culling (`QuadBuckets`)**: Reorders quads into `[EAST, UP, SOUTH, UNASSIGNED, NORTH, DOWN, WEST]` so any visible camera octant coalesces into at most **3 contiguous draw spans**, cutting ~45% of back-facing rasterizer work.
  - **18&sup3; Bitmask Buried-Block Culling (`BitmaskFaceCuller`)**: Skips tessellation for solid vanilla blocks enclosed by six full opaque neighbors.
  - **Fog Occlusion, Radix Translucent Sort &amp; Visible-Only Sprite Animation**: Skips fog-occluded sections, replaces merge sort with a stable radix distance sort, skips off-screen animated block-atlas blits, and reuses the entire `RegionalChunkBatchPlan` across stationary frames (`TerrainTopology` invalidation).

### 2. Zero-Allocation Vertex Fast-Write &amp; Backend Dispatch Caches

- **Direct Native-Memory Vertex Writers**: Single-pass off-heap emission (`FastQuadWriter`, `FastCuboidWriter`, `FastParticleQuadWriter`, `FastGlyphWriter`, `FastShadowWriter`, `FastLineWriter`) eliminates intermediate scratch arrays and `ThreadLocal` lookups for baked quads, entity cuboids (transforming 8 unique corners and copying side faces directly in native memory), particles, shadows, and text glyphs.
- **Vulkan Dispatch &amp; Buffer-View Caching**: Precompiles per-layout `VulkanDescriptorPlan` templates to push descriptors with contiguous stack allocations and zero per-draw `HashMap.get` lookups, plus a 16-entry per-buffer `VkBufferView` cache on `VulkanGpuBuffer.Direct`.
- **OpenGL Dispatch Cache &amp; `KHR_no_error`**: Pass-scoped tracking skips redundant sampler, UBO, VAO, FBO, scissor, polygon-mode, and draw-buffer calls (resetting every pass so raw GL mods remain safe), and enables `KHR_no_error` on context creation when GL debug logging is off.

### 3. Conservative Occlusion &amp; Entity / Particle Culling

- **`OpaqueLayerSweep` &amp; `SectionBlockCache`**: Proves full-voxel opaque layers separating camera and target bounds using a 256-slot frame-scoped `SectionBlockCache` with lazy 4096-bit section opacity bitsets, bounded at 512 reads and cached in direct-mapped primitive sweep tables. Always fails open for bosses, glowing/named/leashed entities, beacons, global renderers, and modded renderer classes.
- **Particle &amp; Block-State Culling**: `ParticleCellOcclusion` runs a bounded dominant-axis DDA cached in a 4,096-entry direct-mapped cell table alongside tick-paced particle lightmap caching and thread-local rotation quaternions. Additional culling covers deep interior leaves (`DEPTH` / `CHECK_SURROUNDING` / `FAST_OPAQUE`), back-facing sign text, occluded item frames/paintings, rain/snow columns, and extruded cloud allocations.
- **Direct Renderer &amp; Shadow Caches**: Caches entity and block-entity renderer lookups directly on `EntityType` / `BlockEntityType` (zero G1GC write barriers on hits) plus a single-lookup pass cache for players/modded renderers and a 4,096-slot static ground-surface cache for entity shadows.

### 4. GUI, Text &amp; Item Rendering

- **`IndexedRectList` &amp; `GuiBoundsIndex`**: Replaces linear GUI intersection scans (`~12%` of frame time in HUD-heavy scenes) with an incremental flat-`int[]` bounds index + union bounding box, turning misses into 4 integer comparisons with zero `IdentityHashMap` overhead.
- **Text &amp; Font Pipeline**: Pure left-to-right `FastLtrDetector` / `FastBidi` bypasses ICU `Bidi` for standard text, `TextDecorationRunMerger` merges contiguous underline/strikethrough runs, `GuiTextBatcher` batches text runs (preserving linear SDF filtering for Aurora Client), and font atlas pages resize to `1024&times;1024` to stop texture thrashing.
- **Tick-Cached `GuiItemAtlas`**: Keeps animated sprite slots alive across frames (redrawing only on `TextureManager.tick()`), optionally rate-limits enchantment glint redraws, caches resolved `ItemStack` render states copy-free via `PatchedDataComponentMap` copy-on-write tokens, and memoizes atlas slots on hash-caching `ModelIdentityKey`s.

### 5. Frame Pacing, Unified Streaming Budget &amp; Display Presentation

- **Unified Per-Frame Chunk-Streaming Budget (`FrameBudgetPacer`)**: Shares one 3.0 ms uncapped budget (75% snapshot reserve) and a hard display-period deadline on capped frames across both section snapshots and Vulkan mesh uploads, backed by an O(1) two-tier nearby/distant pending upload queue so chunk streaming never tanks 1% / 0.1% lows.
- **Precision Frame Pacer (`EphemeraFramePacer`)**: Double-precision nanosecond cadence (no integer drift at 144/165/240/360 Hz), fast-attack/slow-decay adaptive OS wakeup overshoot estimation, bounded interrupt-safe re-park + `Thread.onSpinWait()` tail, and anti-sawtooth soft phase recovery.
- **Borderless Fullscreen, Win32 HighQoS &amp; Capture Awareness**: Strips DWM border/rounding attributes for exact-resolution Independent Flip borderless fullscreen, opts the render thread out of Windows 11 EcoQoS/Power Throttling with P-core HighQoS scheduling and 1 ms high-resolution waitable timers, and automatically detects active capture tools (OBS, etc.) to trim busy-spin windows and yield cleanly to encoders.

### 6. Common Engine: Memory, Lighting, Worldgen &amp; Networking (`environment: "*"`)

Every common optimization runs on **both clients and dedicated servers**:

- **Memory Deduplication &amp; Leak Guards**: Interns identical `BlockState` caches, property tuples, `VoxelShape` coordinate lists, and baked model quads; releases unused upper sprite mipmaps after atlas upload; trims bloated off-heap `ByteBufferBuilder` allocations; and sweeps world-scoped caches on disconnect/reload.
- **Lighting Engine**: Expands `LightEngine` and `DataLayerStorageMap` lookup caches to 8 slots, uses separate flat ring FIFOs (`FastLightQueue`), and fast-paths uniform light `DataLayer` reads — bit-verified against vanilla across 221,184 sampled positions.
- **Worldgen, Storage &amp; Networking**: Compact 16 KiB sine table (`FastMath`), power-of-two chunk modulo &amp; cached minimum section index, `PalettedContainer` scratch-array pooling, `LinearPalette` primitive index cache, fast noise/aquifer/beardifier/biome/material-system paths (on both 26.2 and 26.3), Netty flush consolidation (256-request threshold), and fast `VarInt` / `VarLong` codecs.

### 7. Built-In Visual Enhancements &amp; Full-Page Video Settings

All visual features are live-configurable, sub-0.05 ms overhead, and automatically yield whenever an Iris/Oculus shader pack is active:

- **Smooth Fog &amp; Interleaved Gradient Dithering**: Hermite smoothstep fog curve with zero-centered IGN dithering across terrain, sky, clouds, entities, and particles to eliminate 8-bit sky/fog banding.
- **Atmospheric Horizon, Enhanced AO, Crisp Anisotropic Mipmaps, Smooth Chunk Fade, Underwater Clarity**: Subtle horizon warmth, tunable AO contrast curve, Blaze3D anisotropic block-atlas samplers (up to 16&times; on both backends), cubic ease-out chunk fade-in, and optional extended underwater visibility.
- **Dynamic Lights &amp; Optional Held World Light**: Zero-chunk-rebuild entity/hand dynamic lights via a 16-block spatial hash (`DynamicLightGrid`), plus an optional opt-in **Held World Light** shader mode that illuminates nearby terrain around your held light source with wall-leak clamping.
- **Full-Page Video Settings UI (`EphemeraSettingsScreen`)**: Replaces vanilla Video Settings with a tabbed sidebar carrying every Minecraft Display &amp; Quality option (including sliders for GUI Scale and Windowed / Borderless / Exclusive fullscreen mode) alongside dedicated Ephemera Performance, Terrain, Culling, Engine &amp; Memory, Visuals, and live **Diagnostics** pages (FPS, median, p99, 1%/0.1% lows, mixin health, occlusion counters, and optional HUD overlay). The original vanilla screen stays one click away.

---

## Repository &amp; Multi-Version Architecture

Ephemera builds per-version runtime JARs from one shared codebase (`ephemera-<version>-mc<minecraft>.jar`):

| Module / Directory | Scope |
|---|---|
| [`:api`](api/src/main/java/com/ephemera/api) | Zero-dependency public SPI (`EphemeraApi`, `EphemeraCapabilities`, `EphemeraSettingsBridge`, `EphemeraVisibilityProvider`, `EphemeraShaderHooks`) |
| [`:core`](core/src/main/java/com/ephemera/core) | Pure Java 25 algorithms with **zero** Minecraft/Fabric/LWJGL/Mixin imports (enforced by `:core:verifyLayering` and backed by 46 JUnit test suites) |
| [`src/main`](src/main/java/com/ephemera) | Common (`*`) chunk storage, block-state/shape interning, lighting, worldgen, and networking mixins |
| [`src/client`](src/client/java/com/ephemera) | Shared client culling, GUI/text/item caches, frame pacing, Win32 display runtime, visuals, settings UI, and `ObjectShare` bridges |
| [`versions/26.2`](versions/26.2) &amp; [`versions/26.3`](versions/26.3) | Version overlays bridging Blaze3D (26.2 / GLFW) and RenderPearl (26.3 / SDL3 + OIT) via `RenderPlatform` and build-time class derivation (`derive.json`). See [docs/multi-version.md](docs/multi-version.md). |

Detailed technical module specifications live in [`docs/modules/`](docs/modules):
[terrain-eng.md](docs/modules/terrain-eng.md) &middot; [backend-fastwrite.md](docs/modules/backend-fastwrite.md) &middot; [culling-entities.md](docs/modules/culling-entities.md) &middot; [gui-text-items.md](docs/modules/gui-text-items.md) &middot; [engine-mem-light.md](docs/modules/engine-mem-light.md) &middot; [visuals-ui.md](docs/modules/visuals-ui.md)

---

## Compatibility, Stand-Downs &amp; Aurora Integration

Ephemera uses a fail-soft mixin plugin (`EphemeraCommonMixinPlugin` / `EphemeraClientMixinPlugin`) that automatically stands down overlapping mixins when specialized mods are detected:

| Subsystem | Automatically Stands Down For |
|---|---|
| **Terrain &amp; GPU Dispatch** | `sodium`, `vulkanmod`, `iris`, `oculus` (no-error/shader hooks), `canvas`, `nvidium` |
| **Native Vertex Fast-Write** | `sodium`, `iris`, `oculus`, `vulkanmod`, `immediatelyfast` |
| **Entity / Block / Particle Culling** | `entityculling`, `moreculling`, `particle_core`, `badoptimizations` |
| **GUI, Text &amp; Item Atlas** | `immediatelyfast`, `modernui`, `caxton`, `gnetum`, `polytone`, `dynamic_fps` |
| **Memory, Lighting, Worldgen &amp; Net** | `ferritecore`, `modernfix`, `lithium`, `radium`, `canary`, `c2me`, `noisium`, `vmp`, `scalablelux`, `starlight`, `krypton`, `rrls`, `fastload`, `fast-ip-ping` |
| **Borderless &amp; Dynamic Lights** | `borderless-mining`, `cubes-without-borders`, `fullscreen-windowed`, `lambdynlights`, `dynamiclights`, `ryoamiclights`, `sodiumdynamiclights` |

### Aurora Client Zero-Coupling Integration

When paired with [Aurora Client](https://github.com/kalibsolomon-pixel/Aurora-Client):
- Ephemera publishes `ephemera:fast_write`, `ephemera:frame_pacer`, and `ephemera:settings` via Fabric's `ObjectShare`, accelerating Aurora's SDF text/hitbox rendering, coordinating single-owner frame pacing without double-sleeping, and exposing Ephemera toggles inside Aurora's Settings UI with zero compile-time coupling.
- Builds advertise `ephemera:engine_ownership` and `ephemera:world_visual_ownership` in `fabric.mod.json` so updated Aurora clients yield overlapping hooks while Ephemera preserves linear SDF glyph filtering in `GuiTextBatcher`.

---

## Installation, Building &amp; Benchmarking

### Installation
1. Install **Fabric Loader 0.20.0+** for **Minecraft 26.2 or 26.3** (Java 25+).
2. Download the matching `ephemera-<version>-mc<minecraft>.jar` from [GitHub Releases](https://github.com/kalibsolomon-pixel/Ephemera/releases/latest) and place it in your `mods/` folder.
3. *(Optional)* Switch between **Vulkan** and **OpenGL** under **Video Settings &rarr; Display &rarr; Graphics API**.

### Building from Source

```bash
# Build & test default target (Minecraft 26.2)
./gradlew check build

# Build & test Minecraft 26.3
./gradlew -Pephemera.minecraft=26.3 check build
```

Output JARs:
- `build/libs/ephemera-<version>-mc26.2.jar`
- `build/minecraft-26.3/libs/ephemera-<version>-mc26.3.jar`

### Running the Benchmark Suite

```bash
# RD 20 (8 workloads) + RD 32 (2 workloads), uninstrumented
python tools/bench/run_bench.py --targets vanilla,sodium,ephemera --backends VULKAN,OPENGL --suite all --render-distance 20 --duration 4 --no-jfr
python tools/bench/run_bench.py --targets vanilla,sodium,ephemera --backends VULKAN,OPENGL --suite default --render-distance 32 --duration 3 --no-jfr
```

---

## License

MIT &mdash; see [LICENSE](LICENSE).
