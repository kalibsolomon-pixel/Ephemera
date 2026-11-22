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
- Version-specific mixins get their own mixin config in the overlay. Every version must ship
  each config named in `fabric.mod.json`, even if its `mixins` list is empty
  (see `ephemera.worldgen.mixins.json`).

## Porting status (2026-11-22)

| Minecraft | Status | Compile errors against shared `src/` |
|---|---|---|
| 26.2 | supported | 0 |
| 26.3 | porting | ~800 in 65 files: GL/Vulkan backend (~330), terrain (~310), fast-write, GUI, visuals |
| 26.1.2 | porting | ~1,060 in 59 files: Vulkan mesh terrain (~390), backend (~520), terrain, fast-write |

On both, the worldgen mixins (`versions/26.2/.../mixin/common/worldgen`) are 26.2-only for
now, because 26.3 moved `DensityFunction` and dropped or reshaped `SurfaceRules`,
`ImprovedNoise` and `BiomeManager.NoiseBiomeSource`.

Suggested porting order for a version: move each failing file into `versions/26.2/`, add a
ported copy under `versions/<minecraft>/`, and do the cheap cascades first (renamed types),
then the GL backend, then Vulkan.
