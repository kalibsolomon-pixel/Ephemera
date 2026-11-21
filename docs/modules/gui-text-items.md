# GUI layering, text and item optimizer (`gui-text-items`)

Minecraft 26.2 (unobfuscated, Mojang names). Targets the GUI hot spots measured in the `gui_pvp_hud`,
`terrain_vista_rd20` and `dense_world_crowd` JFR captures.

| Hotspot | Fix |
|---|---|
| `GuiRenderState.findAppropriateNode` / `hasIntersection` / `navigateToAboveHighestElementWithIntersectingBounds` (~12% of frame) | Per-list flat-`int[]` bounds index with a union-box reject (`GuiBoundsIndex` / `GuiLayerIndex`) |
| Boxed `Comparator.comparing` chain in `GuiRenderState.sortElements` | Allocation-free `GuiElementOrder` with identical ordering |
| `FormattedBidiReorder.reorder` + ICU `Bidi` | Pure left-to-right fast path (`FastLtrDetector` / `FastBidi`) |
| One underline/strikethrough quad per glyph | Contiguous identical quads merged (`TextDecorationRunMerger`) |
| 256x256 glyph pages thrashing between textures | Glyph pages sized to `guiFontAtlasSize` (default 1024) |
| `GuiItemAtlas` redrawing every animated item every frame | Slots kept; redrawn only when `TextureManager.tick()` advances (sprites) or on the glint period |
| `ItemModelResolver.updateForTopItem` per static stack per frame | Optional per-tick resolved-state reuse (`GuiItemStateCache`) |
| Background-window FPS | Optional unfocused FPS cap |

## Mixin classes

All names are relative to `com.ephemera.mixin.client`; register them in `ephemera.client.mixins.json`
as `gui.<Name>`. Every mixin reads the `GuiFeatures` snapshot at runtime, so a disabled config option
makes the injection a single field test even if the mixin is applied.

| Mixin (`gui.`) | Target | Config gate | `yieldsTo` |
|---|---|---|---|
| `GuiRenderStateMixin` | `GuiRenderState.hasIntersection` (indexed), `reset` (clears per-frame index) | `guiLayerIndexing` | `immediatelyfast`, `modernui`, `gnetum` |
| `GuiRendererMixin` | `GuiRenderer.prepare` -> `sortElements` argument (unboxed comparator) | `guiSkipTextTranslucencySort` | `immediatelyfast`, `modernui` |
| `FormattedBidiReorderMixin` | `FormattedBidiReorder.reorder` | `guiFastLtrText` | `modernui`, `caxton` |
| `FontPreparedTextBuilderMixin` | `Font$PreparedTextBuilder.accept` (two `createEffect` call sites) | `guiMergeTextDecorations` | `modernui`, `caxton`, `immediatelyfast` |
| `FontTextureMixin` | `FontTexture.<init>` / `add` (256 -> page size) | `guiFontAtlasResizing`, `guiFontAtlasSize` | `modernui`, `caxton`, `immediatelyfast` |
| `GuiItemAtlasMixin` | `GuiItemAtlas.getOrUpdate` (wraps `DynamicAtlasAllocator.getOrAllocate`) | `guiAnimatedItemBaking`, `guiGlintRateLimit`, `guiGlintFps` | `immediatelyfast`, `polytone` |
| `AtlasSlotMixin` | `DynamicAtlasAllocator.Slot` (adds content version + stale marker) | with `GuiItemAtlasMixin` | same as `GuiItemAtlasMixin` |
| `TextureManagerMixin` | `TextureManager.tick` (tick counter) | always on (counter only) | none |
| `GuiGraphicsExtractorMixin` | private `item(LivingEntity, Level, ItemStack, int, int, int)` | `guiHudFrameCache` (opt-in, default off) | `immediatelyfast`, `gnetum`, `polytone` |
| `FramerateLimitTrackerMixin` | `FramerateLimitTracker.getFramerateLimit` | `unfocusedFpsLimitEnabled`, `unfocusedFpsLimit` | `dynamic_fps` |
| `ItemStackRenderStateAccessor`, `LayerRenderStateAccessor` | accessors (`layers`, `activeLayerCount`, `foilType`) | none | none |

`GuiItemAtlasMixin` and `AtlasSlotMixin` must always share the same yield rules; the manager tolerates a
missing slot mixin (it falls back to vanilla behavior) but not the reverse.

Suggested plugin registration (`EphemeraClientMixinPlugin.onLoad`):

```java
registerYield("GuiRenderStateMixin", "immediatelyfast", "modernui", "gnetum");
registerYield("GuiRendererMixin", "immediatelyfast", "modernui");
registerYield("FormattedBidiReorderMixin", "modernui", "caxton");
registerYield("FontPreparedTextBuilderMixin", "modernui", "caxton", "immediatelyfast");
registerYield("FontTextureMixin", "modernui", "caxton", "immediatelyfast");
registerYield("GuiItemAtlasMixin", "immediatelyfast", "polytone");
registerYield("AtlasSlotMixin", "immediatelyfast", "polytone");
registerYield("GuiGraphicsExtractorMixin", "immediatelyfast", "gnetum", "polytone");
registerYield("FramerateLimitTrackerMixin", "dynamic_fps");
```

## Static `init()` hook

Call `com.ephemera.render.gui.GuiFeatures.init()` once from `EphemeraClient.onInitializeClient`. It snapshots
the `gui*` / `unfocusedFps*` settings. Mixins that run before it (first frame, resource load) initialise the
snapshot lazily through `GuiFeatures.get()`, so the call only fixes the moment the config is read. Settings are
fixed for the session (font page size in particular is read when a page is created).

## Files

Pure Java core (`core/src/main/java/com/ephemera/core/gui`, no MC/Fabric/Mixin imports):

- `GuiBoundsIndex` - append-only flat `int[]` rectangles plus union box; `intersectsAny` uses the exact
  `ScreenRectangle.intersects` predicate, no per-query allocation.
- `FastLtrDetector` - conservative "cannot reorder" check (rejects RTL/AL letters, Arabic-Indic digits, all bidi
  controls, paragraph/segment separators, unassigned code points; with a default-RTL paragraph it also requires a
  strong LTR character).
- `TextDecorationRunMerger` - merges a decoration quad into the open run only when vertical extent, depth, both
  colors and shadow offset match and the quad starts exactly where the run ends (touching, never overlapping, so
  translucent output is unchanged).

Client (`src/client/java/com/ephemera/render/gui`): `GuiFeatures`, `GuiLayerIndex`, `GuiElementOrder`, `FastBidi`,
`GuiItemBakingManager`, `GuiAtlasSlotState`, `GuiItemStateCache`, `UnfocusedFpsController`.

Tests (`core/src/test/java/com/ephemera/core/gui`): randomized `GuiBoundsIndex` parity against the vanilla
predicate (including degenerate/negative/overflow-edge rectangles), `FastLtrDetector` parity against the JDK bidi
implementation over every BMP code point and 12,000 random mixed-script strings (both paragraph directions),
plus run-merge tests.

## Behavior notes

- **Animated items.** Vanilla flags both sprite-animated and enchantment-glint items as `animated`, which makes
  `DynamicAtlasAllocator` free their slot every frame. Sprite-only items now keep the slot and are redrawn once per
  `TextureManager.tick()` (sprite animation only advances there). Glint items keep vanilla's per-frame redraw unless
  `guiGlintRateLimit` is on, then they redraw once per `1 / guiGlintFps` seconds. Compass/clock-style items are
  property-dispatched models with distinct model identities and are unaffected.
- **Item state cache** is opt-in (`guiHudFrameCache`). A resolved state is reused only within one client tick and
  only if level, owner, seed, item and components still match, so output can lag a change by at most the rest of
  the tick.
- **`guiSkipTextTranslucencySort`.** The 26.2 GUI text path has no separate translucency sort (glyph quads live in
  `glyphStates`, which vanilla never sorts). The flag therefore gates the unboxed element comparator, the only sort
  on the GUI text path.
- **Font pages** use `Integer.highestOneBit(clamp(guiFontAtlasSize, 256, 4096))` for both allocation and UVs.
- **Pure-LTR bidi** output is the single forward run vanilla's `Bidi` produces for such text; any string the
  detector is unsure about takes the unchanged vanilla path.

## Verification

```
powershell -NoProfile -Command "& ./tools/check_worker.ps1 gui-text-items -CoreDirs core/src/main/java/com/ephemera/core/gui,core/src/test/java/com/ephemera/core/gui -ClientDirs src/client/java/com/ephemera/render/gui,src/client/java/com/ephemera/mixin/client/gui"
```

`powershell -File ... -CoreDirs a,b` passes `a,b` as a single string and silently compiles nothing (it still prints
`CHECK OK`); use `-Command "& ..."` as above. Mixin application is not exercised by this check; it needs a client launch.
