# Ephemera

Clean-room, MIT-licensed Minecraft performance engine for Fabric (`environment: "*"`, Java 25, Minecraft 26.2+) supporting both **native Vulkan** and **OpenGL 3.3 Core**. Built as a standalone Fabric mod and the default managed performance engine bundled by [Aurora Launcher](https://github.com/kalibsolomon-pixel/Aurora-Launcher) alongside [Aurora Client](https://github.com/kalibsolomon-pixel/Aurora-Client).

## Architecture

| Module | Scope |
|---|---|
| `:api` | Zero-dependency public SPI (`EphemeraApi`, `EphemeraCapabilities`, `EphemeraSettingsBridge`, `EphemeraVisibilityProvider`, `EphemeraShaderHooks`) |
| `:core` | Pure Java 25 algorithms with zero Minecraft/Fabric/LWJGL/Mixin imports (verified by `:core:verifyLayering`): frustum/fog culling, meshlet baking, directional face bucketing, slab allocation, paletted storage, noise/biome math, GUI bounds indexing |
| `src/main` | Common (client + dedicated server) chunk storage, lighting, worldgen noise/biome, and network optimizations |
| `src/client` | Native Vulkan (`VK_EXT_mesh_shader` single-pass Task+Mesh terrain, ReBAR ring buffers, zero-alloc push-descriptor dispatch) and OpenGL regional multi-draw, entity/block-entity/particle occlusion, batched native vertex fast-write, and zero-coupling `ObjectShare` integration bridges for Aurora Client |

## Compatibility & Stand-Downs

Ephemera uses a fail-soft mixin plugin (`EphemeraCommonMixinPlugin` / `EphemeraClientMixinPlugin`) that automatically stands down overlapping areas when specialized mods are installed (`sodium`, `iris`, `vulkanmod`, `lithium`, `c2me`, `noisium`, `immediatelyfast`, `entityculling`, `moreculling`, `ferritecore`, `scalablelux`, `krypton`, `aurora`).

When paired with Aurora Client:
- Ephemera publishes `ephemera:fast_write` and `ephemera:settings` via Fabric's `ObjectShare`, accelerating Aurora's SDF text and SDF hitbox/outline rendering with zero compile-time coupling and exposing Ephemera controls inside Aurora's Settings UI.
- Aurora Client yields overlapping engine optimizations (`perfEntityOcclusion`, `perfGlDispatch`, `perfGuiLayering`, etc.) to Ephemera while retaining full ownership of its SDF text, SDF hitboxes/outlines, glass UI, HUD modules, and PvP features.

## Building & Testing

```bash
./gradlew check build
```

Output JAR: `build/libs/ephemera-<version>-mc<minecraft_version>.jar`

## License

MIT — see [LICENSE](LICENSE).
