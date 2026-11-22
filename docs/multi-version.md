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
| 26.2 | supported | Full feature set. |
| 26.3 | supported (not at parity) | Builds, launches and loads worlds on OpenGL and Vulkan with no mixin failures. Performance is still below 26.2; see below. |
| 26.1.2 | porting | Not started: about 1,060 compile errors against shared `src/`, mostly the Vulkan mesh terrain and backend code. |

### 26.3 performance vs 26.2 (2026-11-22)

Bench matrix (`tools/bench/run_bench.py --suite all --minecraft 26.2,26.3`, two runs each,
compared with `tools/bench/compare_versions.py`) before derivation: 26.3 trailed 26.2 by
8-36% on OpenGL and 33-59% on Vulkan (31 of 32 scene/metric checks failed). Vanilla 26.3 is
itself faster than vanilla 26.2; the gap is Ephemera features missing on 26.3. After deriving
the Vulkan backend chain (device features, checkpoint gating, async submit/present, barriers),
a short Vulkan run recovered 15-43% but still trails 26.2.

### 26.3 feature gap

26.3 moved the GPU layer from `com.mojang.blaze3d.{systems,opengl,vulkan,...}` to
`com.mojang.renderpearl.{api,backend,frontend}`, replaced GLFW with SDL, and added its own
multi-draw terrain path (`ChunkSectionLayer.pipeline(boolean)`, abstract
`ChunkSectionsToRender`, terrain transform UBO, OIT). Derived to 26.3 so far: the GL dispatch
and state caches, the Vulkan device features, checkpoint gating, async submit/present, encoder
barriers, buffer views and direct-buffer mapping, the SPIR-V optimization level, fast-write
writers for cuboids/glyphs/lines/particles/shadows, GUI text batching, the texture-setup
cache and anisotropy. Still missing on 26.3 (see `versions/26.3/derive.json` `exclude`):

- Vulkan render-pass bind caching and descriptor templates (26.3 rebuilt `VulkanRenderPass`
  around slot-indexed uniforms and `BackendRenderPipeline`)
- the Ephemera terrain engine (chunk batcher, OpenGL single multi-draw, Vulkan mesh shaders,
  section mesh cache, fast section compiler, visible-sprite animation), which needs a redesign
  for 26.3's new terrain path rather than a rename
- fast-write quad paths for items, decals and sprites (blocked on the sprite tracker)
- the OpenGL no-error context (GLFW to SDL) and terrain shader patching
- worldgen noise/surface-rule/biome-zoom mixins (`DensityFunction` moved; `SurfaceRules`,
  `ImprovedNoise`, `NoiseChunk.NoiseInterpolator` and `BiomeManager.NoiseBiomeSource` were
  removed or reshaped)

Ported by hand for 26.3 (`versions/26.3/src`): entity frustum/occlusion culling, decoration
culling, lightmap shader reload, chunk fade easing, Vulkan device creation, `RenderPlatform`.

Suggested order for the rest: Vulkan render-pass caching (largest remaining Vulkan and GUI
gap), then the terrain engine against 26.3's multi-draw path, then worldgen and the rest.
