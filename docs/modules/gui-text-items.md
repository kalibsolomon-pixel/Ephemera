# GUI layering, text and item optimizer (`gui-text-items`)

Minecraft 26.2 (unobfuscated, Mojang names). Targets the GUI hot spots measured in the `gui_pvp_hud`,
`terrain_vista_rd20` and `dense_world_crowd` JFR captures.

| Hotspot | Fix |
|---|---|
| `GuiRenderState.findAppropriateNode` / `hasIntersection` / `navigateToAboveHighestElementWithIntersectingBounds` (~12% of frame) | Node layer lists are created as `IndexedRectList`s that keep a flat-`int[]` bounds index + union box incrementally (`GuiBoundsIndex`); a query is `instanceof` + 4 int compares for a miss, no `IdentityHashMap`, no per-frame side-table entries. `GuiLayerIndex` (side table) remains the fallback for foreign lists |
| `GuiLayerIndex.intersects` `IdentityHashMap.get` on every `hasIntersection` (1.83% on `gui_pvp_hud`) | Removed by the above |
| Boxed `Comparator.comparing` chain in `GuiRenderState.sortElements` | Allocation-free `GuiElementOrder` with identical ordering |
| `FormattedBidiReorder.reorder` + ICU `Bidi` | Pure left-to-right fast path (`FastLtrDetector` / `FastBidi`) |
| One underline/strikethrough quad per glyph | Contiguous identical quads merged (`TextDecorationRunMerger`); the two mergers are created on the first decorated glyph instead of per text line |
| 256x256 glyph pages thrashing between textures | Glyph pages sized to `guiFontAtlasSize` (default 1024) |
| `GuiItemAtlas` redrawing every animated item every frame | Slots kept; redrawn only when `TextureManager.tick()` advances (sprites) or on the glint period |
| `ItemModelResolver.appendItemLayers` 4.74% + `GuiGraphicsExtractor.item` 1.15% (every hotbar/armor/HUD stack re-resolved every frame because the cache was opt-in) | `GuiItemStateCache` is on whenever `guiAnimatedItemBaking \|\| guiHudFrameCache`; allocation-free, copy-free per-tick reuse |
| `ArrayList.hashCodeRange` 1.15% (model-identity `ArrayList` hashed/compared by `HashSet.add`, `Sets.union`, `HashMap.get` for every item every frame) | `ModelIdentityKey`: same equality, hash cached; plus a per-key atlas-slot memo that skips the map probe |
| Background-window FPS | Optional unfocused FPS cap |

## Mixin classes

All names are relative to `com.ephemera.mixin.client`; register them in `ephemera.client.mixins.json`
as `gui.<Name>`. Every mixin reads the `GuiFeatures` snapshot at runtime, so a disabled config option
makes the injection a single field test even if the mixin is applied.

| Mixin (`gui.`) | Target | Config gate | `yieldsTo` |
|---|---|---|---|
| `GuiRenderStateMixin` | `GuiRenderState.hasIntersection` (indexed), `reset` (clears per-frame index) | `guiLayerIndexing` | `immediatelyfast`, `modernui`, `gnetum` |
| `GuiRenderStateNodeMixin` **(new)** | `GuiRenderState$Node.addItem/addText/addPicturesInPictureState/addGuiElement`: wraps the lazy `new ArrayList` so the list is an `IndexedRectList` | `guiLayerIndexing` | same as `GuiRenderStateMixin`: `immediatelyfast`, `modernui`, `gnetum` |
| `GuiRendererMixin` | `GuiRenderer.prepare` -> `sortElements` argument (unboxed comparator) | `guiSkipTextTranslucencySort` | `immediatelyfast`, `modernui` |
| `FormattedBidiReorderMixin` | `FormattedBidiReorder.reorder` | `guiFastLtrText` | `modernui`, `caxton` |
| `FontPreparedTextBuilderMixin` | `Font$PreparedTextBuilder.accept` (two `createEffect` call sites) | `guiMergeTextDecorations` | `modernui`, `caxton`, `immediatelyfast` |
| `FontTextureMixin` | `FontTexture.<init>` / `add` (256 -> page size) | `guiFontAtlasResizing`, `guiFontAtlasSize` | `modernui`, `caxton`, `immediatelyfast` |
| `GuiItemAtlasMixin` | `GuiItemAtlas.getOrUpdate` (wraps `DynamicAtlasAllocator.getOrAllocate`; slot memo fast path) | `guiAnimatedItemBaking`, `guiGlintRateLimit`, `guiGlintFps` | `immediatelyfast`, `polytone` |
| `AtlasSlotMixin` | `DynamicAtlasAllocator.Slot` (adds content version, stale marker, owner key, `touch`) | with `GuiItemAtlasMixin` | same as `GuiItemAtlasMixin` |
| `DynamicAtlasAllocatorMixin` **(new)** | `DynamicAtlasAllocator`: stamps the owner key in `getOrAllocate` (wraps the `Map.put`), adds `ephemera$revalidate` (liveness via `freeSlots`) | with `GuiItemAtlasMixin` | same as `GuiItemAtlasMixin`: `immediatelyfast`, `polytone` |
| `TrackingItemStackRenderStateMixin` **(new)** | `TrackingItemStackRenderState.getModelIdentity` (returns a hash-caching `ModelIdentityKey`), `appendModelIdentityElement` (invalidates it) | `guiAnimatedItemBaking \|\| guiHudFrameCache` | same as `GuiItemAtlasMixin`: `immediatelyfast`, `polytone` |
| `PatchedDataComponentMapMixin` **(new)** | `PatchedDataComponentMap` (`GuiPatchSnapshot` duck: patch reference token + arm `copyOnWrite`) | used by the item state cache | same as `GuiGraphicsExtractorMixin`: `immediatelyfast`, `gnetum`, `polytone` |
| `TextureManagerMixin` | `TextureManager.tick` (tick counter) | always on (counter only) | none |
| `GuiGraphicsExtractorMixin` | private `item(LivingEntity, Level, ItemStack, int, int, int)` | `guiAnimatedItemBaking \|\| guiHudFrameCache` (on by default) | `immediatelyfast`, `gnetum`, `polytone` |
| `FramerateLimitTrackerMixin` | `FramerateLimitTracker.getFramerateLimit` | `unfocusedFpsLimitEnabled`, `unfocusedFpsLimit` | `dynamic_fps` |
| `ItemStackRenderStateAccessor`, `LayerRenderStateAccessor` | accessors (`layers`, `activeLayerCount`, `foilType`) | none | none |

`GuiItemAtlasMixin`, `AtlasSlotMixin`, `DynamicAtlasAllocatorMixin` and `TrackingItemStackRenderStateMixin` should
share the same yield rules. Missing pieces degrade safely: without `AtlasSlotMixin`/`DynamicAtlasAllocatorMixin` the
atlas wrapper skips the slot memo and calls vanilla `getOrAllocate` (the kept-slot logic needs `AtlasSlotMixin`, as
before); without `TrackingItemStackRenderStateMixin` keys are the raw lists (no memo); without
`PatchedDataComponentMapMixin` the item state cache never hits. `GuiRenderStateNodeMixin` without
`GuiRenderStateMixin` only means the lists keep their own index while vanilla scans them linearly.

**New mixins to register in `ephemera.client.mixins.json` (client array, `gui.` prefix):**
`GuiRenderStateNodeMixin`, `TrackingItemStackRenderStateMixin`, `DynamicAtlasAllocatorMixin`,
`PatchedDataComponentMapMixin`. The previously registered `GuiRenderStateMixin`, `GuiItemAtlasMixin`,
`AtlasSlotMixin`, `GuiGraphicsExtractorMixin` keep their names.

Suggested plugin registration (`EphemeraClientMixinPlugin.onLoad`):

```java
registerYield("GuiRenderStateMixin", "immediatelyfast", "modernui", "gnetum");
registerYield("GuiRenderStateNodeMixin", "immediatelyfast", "modernui", "gnetum");
registerYield("GuiRendererMixin", "immediatelyfast", "modernui");
registerYield("FormattedBidiReorderMixin", "modernui", "caxton");
registerYield("FontPreparedTextBuilderMixin", "modernui", "caxton", "immediatelyfast");
registerYield("FontTextureMixin", "modernui", "caxton", "immediatelyfast");
registerYield("GuiItemAtlasMixin", "immediatelyfast", "polytone");
registerYield("AtlasSlotMixin", "immediatelyfast", "polytone");
registerYield("DynamicAtlasAllocatorMixin", "immediatelyfast", "polytone");
registerYield("TrackingItemStackRenderStateMixin", "immediatelyfast", "polytone");
registerYield("GuiGraphicsExtractorMixin", "immediatelyfast", "gnetum", "polytone");
registerYield("PatchedDataComponentMapMixin", "immediatelyfast", "gnetum", "polytone");
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
  `ScreenRectangle.intersects` predicate, no per-query allocation. The union box is maintained from the first
  member, and with one member a union hit is already exact (no loop). `clear()` keeps the backing array.
- `IndexedRectList<T>` - `ArrayList` subclass that appends to a `GuiBoundsIndex` in `add(E)`; any other
  mutation (`modCount` change, `set`) triggers a full rebuild on the next query, so it always equals a linear scan.
- `ModelIdentityKey` - wrapper with the wrapped list's `equals`/`hashCode`, hash computed once per change, plus a
  one-entry (owner, value) memo used for the atlas slot.
- `FastLtrDetector` - conservative "cannot reorder" check (rejects RTL/AL letters, Arabic-Indic digits, all bidi
  controls, paragraph/segment separators, unassigned code points; with a default-RTL paragraph it also requires a
  strong LTR character).
- `TextDecorationRunMerger` - merges a decoration quad into the open run only when vertical extent, depth, both
  colors and shadow offset match and the quad starts exactly where the run ends (touching, never overlapping, so
  translucent output is unchanged).

Client (`src/client/java/com/ephemera/render/gui`): `GuiFeatures`, `GuiLayerIndex`, `GuiElementOrder`, `FastBidi`,
`GuiItemBakingManager`, `GuiAtlasSlotState`, `GuiSlotLookup`, `GuiPatchSnapshot`, `GuiItemStateCache`,
`UnfocusedFpsController`.

Tests (`core/src/test/java/com/ephemera/core/gui`): randomized `GuiBoundsIndex` parity against the vanilla
predicate (including degenerate/negative/overflow-edge rectangles, single-member exactness, reuse across 300
clear cycles), `IndexedRectList` parity under random add/insert/remove/set/sort/shuffle/iterator-remove/subList
operations, `ModelIdentityKey` hash-once/equality/memo tests, `FastLtrDetector` parity against the JDK bidi
implementation over every BMP code point and 12,000 random mixed-script strings (both paragraph directions),
plus run-merge tests.

## Behavior notes

- **Animated items.** Vanilla flags both sprite-animated and enchantment-glint items as `animated`, which makes
  `DynamicAtlasAllocator` free their slot every frame. Sprite-only items now keep the slot and are redrawn once per
  `TextureManager.tick()` (sprite animation only advances there). Glint items keep vanilla's per-frame redraw unless
  `guiGlintRateLimit` is on. Moving, visible glint then advances at `guiGlintFps`. Changes to strength or speed
  refresh the slot, and sprite animations still follow texture ticks. Compass and clock-style items use separate
  model identities for their property states.
- **Item state cache** is active when `guiAnimatedItemBaking` (default true) or `guiHudFrameCache` is on. A resolved
  state is reused only within one client tick (`TextureManager.tick()` counter) and only if the very same
  `ItemStack` instance still has the same count, level, owner and seed and an unchanged component patch. The item of
  a stack is final, so identity covers it. Component changes are detected without copying the stack: on store, the
  stack's `PatchedDataComponentMap` gets `copyOnWrite` armed and its patch reference remembered; every mutator
  (`set`, `remove`, `applyPatch`, `restorePatch`, `clearPatch`, `setAll`) goes through `ensureMapOwnership`, which
  replaces the patch when armed, so a reference comparison catches any change (durability, enchantments, ...). A
  stack the owner is currently using (`owner.isUsingItem()` and the same item) is never cached, so bow pull / eating
  animations update every frame. The table is direct-mapped by identity hash (512 slots, colliding stacks just
  re-resolve) and nothing is allocated on a hit. Properties that read real-time input (e.g. a resource pack's
  `keybind_down`) can lag by up to one client tick (50 ms), as the cache was always documented to.
- **Model identity keys.** `getModelIdentity()` returns a `ModelIdentityKey` instead of the raw list. Equality and
  hash are those of the list, so atlas sharing between identical items is unchanged; the hash is walked once per
  resolved state instead of for each of `GuiRenderState.addItem`, `GuiItemAtlas.hasSpaceForAll`, `getOrAllocate` and
  the oversized-item map. The state cache keeps the same state (and key) alive across the frames of a tick.
- **Atlas slot memo.** The key remembers the atlas allocator and slot it last resolved to. It is trusted only if the
  slot's bit in the allocator's `freeSlots` is still clear and the key stamped on the slot at allocation
  (`getOrAllocate`'s `Map.put` is wrapped, so every caller stamps it) equals this key; a slot that was freed and handed
  to another item fails one of the two checks. A valid memo applies exactly the hit-side effects of `getOrAllocate`
  (`discardAfterFrame |= flag`, state `READY`). Anything else takes the real `getOrAllocate`.
- **Layer lists.** The four lists a node scans are `IndexedRectList`s keeping their own bounds index (append =
  incremental update). `glyphStates` is never queried and stays a plain `ArrayList`. `ScreenArea.bounds()` is read
  at append time; vanilla already reads it right before appending (`findAppropriateNode`), and the value is a
  constant of the element.
- **`guiSkipTextTranslucencySort`.** The 26.2 GUI text path has no separate translucency sort (glyph quads live in
  `glyphStates`, which vanilla never sorts). The flag therefore gates the unboxed element comparator, the only sort
  on the GUI text path.
- **Font pages** use `Integer.highestOneBit(clamp(guiFontAtlasSize, 256, 4096))` for both allocation and UVs.
- **Pure-LTR bidi** output is the single forward run vanilla's `Bidi` produces for such text; any string the
  detector is unsure about takes the unchanged vanilla path.
- **Text audit (`gui_pvp_hud`).** `GuiElementOrder` is allocation-free and already matches vanilla ordering;
  `FastBidi` does one `getString()` plus one `SubStringSource` pass per line (kept: the second pass is what makes
  the output identical to vanilla's); `TextDecorationRunMerger` is allocation-free. The only per-line allocation
  left in our text path was the two eagerly created mergers, now lazy.

## Verification

```
powershell -NoProfile -Command "& ./tools/check_worker.ps1 gui-text-items -CoreDirs core/src/main/java/com/ephemera/core/gui,core/src/test/java/com/ephemera/core/gui -ClientDirs src/client/java/com/ephemera/render/gui,src/client/java/com/ephemera/mixin/client/gui"
```

`powershell -File ... -CoreDirs a,b` passes `a,b` as a single string and silently compiles nothing (it still prints
`CHECK OK`); use `-Command "& ..."` as above. Mixin application is not exercised by this check; it needs a client launch.
