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
| 26.3 | supported | Builds, launches and loads worlds on OpenGL and Vulkan with 121 mixins applied, 0 failed. Graphics-layer features are not ported yet (see below). |
| 26.1.2 | porting | Not started: about 1,060 compile errors against shared `src/`, mostly the Vulkan mesh terrain and backend code. |

### 26.3 feature gap

26.3 moved the GPU layer from `com.mojang.blaze3d.{systems,opengl,vulkan,...}` to
`com.mojang.renderpearl.{api,backend,frontend}` and replaced GLFW with SDL. Everything built
on the 26.2 GPU layer is still 26.2-only (`versions/26.2/`, 113 files):

- OpenGL and Vulkan backend optimizations (dispatch/state caches, async Vulkan
  submit/present, descriptor caching, buffer views, no-error context)
- Ephemera terrain engine (OpenGL single multi-draw, regional batching, Vulkan mesh shaders)
  and the chunk section mesh hooks
- Fast vertex writers, GUI text batching and texture-setup cache, Aurora `ephemera:fast_write`
  bridges
- Shader patching (smooth fog, dithering, horizon), anisotropic filtering
- Worldgen noise/surface-rule/biome-zoom mixins (26.3 moved `DensityFunction` and dropped or
  reshaped `SurfaceRules`, `ImprovedNoise` and `BiomeManager.NoiseBiomeSource`)

Ported to 26.3 so far: entity frustum and occlusion culling, decoration culling, lightmap shader
reload, chunk fade easing. Everything else in `src/` (culling, particles, memory, lighting,
collisions, palettes, networking, frame pacing, settings UI, diagnostics) is shared.

Suggested order for the rest: shader patching and anisotropy (small, visual), then the
OpenGL backend and terrain engine against `renderpearl.backend.opengl`, then Vulkan.
