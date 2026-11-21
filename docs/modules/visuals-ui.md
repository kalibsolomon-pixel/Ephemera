# Visuals + Settings UI module

Owns: `core/.../core/visuals`, `src/client/.../visuals`, `src/client/.../ui`,
`src/client/.../mixin/client/{visuals,ui}`, `assets/ephemera/lang/en_us.json`.

Target: Minecraft 26.2 (unobfuscated), Fabric Loader 0.20.1, OpenGL and native Vulkan.
Every feature is gated on `EphemeraCommon.config().visual*` / `diagnostics*` **and** stands down while an
external shader pack (Iris / Oculus) is active.

## Integrator checklist (files this module does not edit)

1. **`ephemera.client.mixins.json`** — add these to `"client"` (package is `com.ephemera.mixin.client`):

   ```json
   "client": [
     "ui.GuiFrameMetricsMixin",
     "ui.HudDiagnosticsMixin",
     "ui.VideoSettingsScreenMixin",
     "visuals.AbstractTextureAccessor",
     "visuals.BlockModelLighterAoMixin",
     "visuals.EntityRendererLightMixin",
     "visuals.RenderSectionFadeMixin",
     "visuals.ShaderSourceMixin",
     "visuals.TextureAtlasAnisotropyMixin",
     "visuals.WaterFogEnvironmentMixin"
   ]
   ```

2. **`EphemeraClient.onInitializeClient`** — call the static init hook once:

   ```java
   com.ephemera.visuals.IrisCompatService.init();   // installs IrisCompatService via EphemeraClient.setShaderHooks(...)
   ```

   The hook is idempotent and also self-installs the first time a shader is patched, so ordering is not critical.
   Without it, `EphemeraApi.shaders()` keeps returning the default hooks until that first shader compile.

3. **`EphemeraClientMixinPlugin.onLoad`** — register the stand-down rules below (optional belt-and-braces; the
   mixins also check at runtime).

No access wideners are needed. No refmap is needed (unobfuscated names).

## Mixins (relative to `com.ephemera.mixin.client`)

| Mixin | Target | Hook | Gate | yieldsTo |
|---|---|---|---|---|
| `visuals.ShaderSourceMixin` | `ShaderManager$CompilationCache` | `getShaderSource` RETURN | smoothFog / dither / horizon | — |
| `visuals.RenderSectionFadeMixin` | `SectionRenderDispatcher$RenderSection` | `getVisibility` RETURN, `setFadeDuration` arg | `visualSmoothChunkFade` | — |
| `visuals.EntityRendererLightMixin` | `EntityRenderer` | `getPackedLightCoords` RETURN | `visualDynamicLights` | `lambdynlights`, `dynamiclights`, `ryoamiclights`, `sodiumdynamiclights` |
| `visuals.BlockModelLighterAoMixin` | `BlockModelLighter` | `prepareQuadAmbientOcclusion` TAIL | `visualEnhancedAmbientOcclusion` | — |
| `visuals.TextureAtlasAnisotropyMixin` | `TextureAtlas` | `upload` TAIL (block atlas only) | `visualCrispMipmaps` + `visualAnisotropicLevel` | — |
| `visuals.AbstractTextureAccessor` | `AbstractTexture` | `@Accessor sampler` (setter) | — | — |
| `visuals.WaterFogEnvironmentMixin` | `WaterFogEnvironment` | `setupFog` TAIL | `visualUnderwaterClarity` | — |
| `ui.VideoSettingsScreenMixin` | `VideoSettingsScreen` | `addOptions` TAIL: adds the "Ephemera..." button | always | — |
| `ui.GuiFrameMetricsMixin` | `Gui` | `extractRenderState(DeltaTracker, boolean, boolean)` HEAD | only samples while HUD/Diagnostics tab is watching | — |
| `ui.HudDiagnosticsMixin` | `Hud` | `extractRenderState(GuiGraphicsExtractor, DeltaTracker)` TAIL | `diagnosticsHudOverlay` | — |

Suggested plugin wiring:

```java
registerYield("EntityRendererLightMixin", "lambdynlights", "dynamiclights", "ryoamiclights", "sodiumdynamiclights");
// isConfigEnabled: ShaderSourceMixin -> smoothFog || gradientDithering || atmosphericHorizon,
//   RenderSectionFadeMixin -> visualSmoothChunkFade, EntityRendererLightMixin -> visualDynamicLights,
//   BlockModelLighterAoMixin -> visualEnhancedAmbientOcclusion, WaterFogEnvironmentMixin -> visualUnderwaterClarity,
//   HudDiagnosticsMixin -> diagnosticsHudOverlay
```

Iris / Oculus are **not** mixin yields: Ephemera's visuals stay loaded and switch off at runtime the moment
`IrisApi.isShaderPackInUse()` is true (polled at most every 250 ms, gates refreshed every 100 ms).

## Features

- **Smooth fog** — `linear_fog_value` returns `t*t*(3-2t)` over exactly `[fogStart, fogEnd]`; the endpoint guards are
  untouched, so terrain, sky, clouds, entities, particles, and underwater all inherit it.
- **Gradient dithering** — `apply_fog` is replaced (vanilla kept as `ephemera_vanilla_apply_fog`) with a version that adds
  interleaved gradient noise `fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))))`
  centred on zero and scaled to `visualDitherStrength / 255`, only where fog is actually blending. This covers
  `sky.fsh`, `terrain.fsh`, and every other shader that goes through `fog.glsl`.
- **Atmospheric horizon** — the fog colour is warmed by a few percent, weighted by `renderDistanceFog * (1 - environmentalFog)`
  so it never tints underwater, lava, or the sky dome.
- **Underwater clarity** — water fog / sky / cloud end distances are scaled by `1.45` in `WaterFogEnvironment.setupFog`.
- **Smooth chunk fade** — `getVisibility` returns `1 - (1 - t)^3`; when vanilla's fade is on, `visualChunkFadeDurationSeconds`
  replaces its duration (0 keeps vanilla's).
- **Dynamic lights** — a 16-block-cell spatial hash (`DynamicLightGrid`, zero allocation per query) of emitters: held items
  (any `BlockItem` by its block's light emission, plus lava bucket and blaze rod), burning entities, spectral arrows,
  glow squids, blazes, magma cubes, fireballs, and dropped light items. Rescanned every `visualDynamicLightsUpdateIntervalMs`
  (min 1 ms). Falloff is `L * (1 - d/L)^2`. Folded into entity / item / hand light coords only; terrain lighting is untouched,
  so there are **zero chunk rebuilds**.
- **Enhanced AO** — the four corner colours of each smooth-lit quad go through `ao^visualAoStrength` via a 256-entry table.
- **Anisotropic filtering** — the block atlas gets a Blaze3D sampler (`GpuDevice.createSampler`) with the configured level,
  clamped to `DeviceLimits.maxAnisotropy()`; minification is LINEAR (anisotropy needs a filtered path), magnification stays
  NEAREST. Samplers are cached for the session. Works on both backends.
- **Iris compat** — `IrisCompatService implements EphemeraShaderHooks`; reflection only (`net.irisshaders.iris.api.v0.IrisApi`),
  no compile-time Iris dependency. `ownsSmoothFog/GradientDithering/DynamicLighting` return `false` while a pack is active.

## When changes take effect

| Setting | Applies |
|---|---|
| Smooth fog, dithering, dither strength, horizon | next shader compile — "Reload Shaders" button (resource reload) or F3+T |
| Underwater clarity, dynamic lights, chunk fade | immediately |
| AO, AO strength | next chunk rebuild (F3+A) |
| Anisotropic level / crisp filtering | immediately (settings screen refreshes the atlas sampler) |

## GLSL safety

The patcher only touches fragment shaders under `core/`, is idempotent (`// ephemera:visuals-patched` marker), and falls
back to the untouched vanilla source if the expected `linear_fog_value` / `apply_fog` shapes are not found. Output uses only
GLSL 330 core constructs (no extensions, uniforms, or samplers), so it goes through both the driver and Blaze3D's
GLSL-to-SPIR-V path. Verified by compiling every patched vanilla fog-using fragment shader with LWJGL shaderc.

## Settings UI

`com.ephemera.ui.EphemeraSettingsScreen` (opened with `minecraft.gui.setScreen(...)`), six tabs:
Performance, Terrain, Culling, Engine & Memory, Visuals, Diagnostics. Every `EphemeraConfig` field appears in exactly one tab,
bound by field name (`ephemera.option.<field>` label, optional `ephemera.tooltip.<field>`).

- Changes apply to the live config immediately; **Save & Close** / Esc persists, **Revert Changes** restores what the screen
  opened with, **Reset Defaults** (two-click) restores `new EphemeraConfig()`.
- Tab / Shift+Tab switch tabs; wheel, arrows, PgUp/PgDn scroll.
- Diagnostics tab: backend (OpenGL/Vulkan), FPS, median, p99, 1% / 0.1% low (`FrameMetrics`), `MixinHealth` summary and failed
  mixins, visible sections and occluded entities / block entities (`EphemeraApi.visibility()`), shader-pack state, dynamic
  light emitters.
- `diagnosticsHudOverlay` draws a compact top-left readout (hidden while F3 is open).
- Entry point: "Ephemera..." button at the bottom of Video Settings. If Sodium (or another mod) replaces the video screen,
  `VideoSettingsScreenMixin` never runs, so another entry point is needed there.

## Not covered here

`visualSmoothBiomeBlending`, `visualBetterLeavesShading`, `visualBetterGrass`, `visualCleanGlassBorders` are exposed in the
Visuals tab but implemented by the terrain engine module.

## Tests

`core/src/test/java/com/ephemera/core/visuals`: `ShaderSourcePatcherTest` (synthetic and real MC 26.2 `fog.glsl`, `sky.fsh`,
`terrain.fsh`, clouds, entity, particle, lines; skipped if `build/mc262-specs/shaders` is absent), `DynamicLightGridTest`,
`ChunkFadeMathTest`.
