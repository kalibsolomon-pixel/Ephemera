# Blaze3D dispatch and native vertex writers

This module targets the unobfuscated Minecraft 26.2 client on Java 25 and Fabric Loader 0.20.1.

## Mixin registration

Add each name below to the client mixin list. Names are relative to `com.ephemera.mixin.client`. Register each `yieldsTo` entry under the mixin's simple name in `EphemeraClientMixinPlugin`. The plugin applies these rules when `modStandDownEnabled` is set.

| Mixin | Config | `yieldsTo` | Target |
| --- | --- | --- | --- |
| `backend.GlCommandEncoderMixin` | `backendGlDispatchCache` | `sodium`, `vulkanmod` | `GlCommandEncoder` |
| `backend.GlRenderPassMixin` | `backendGlDispatchCache` | `sodium`, `vulkanmod` | `GlRenderPass` |
| `backend.GlTextureBindingAccess` | `backendGlDispatchCache` | `sodium`, `vulkanmod` | `GlRenderPass$TextureViewAndSampler` |
| `backend.GlTextureMixin` | `backendGlDispatchCache` | `sodium`, `vulkanmod` | `GlTexture` |
| `backend.FrameBufferCacheMixin` | `backendGlDispatchCache` | `sodium`, `vulkanmod` | `FrameBufferCache` |
| `backend.SeparateVertexArrayCacheMixin` | `backendGlDispatchCache` | `sodium`, `vulkanmod` | `VertexArrayCache$Separate` |
| `backend.MinecraftGlDebugOptionsMixin` | `backendGlNoErrorContext` | `iris`, `oculus`, `vulkanmod` | `Minecraft` |
| `backend.WindowGlNoErrorMixin` | `backendGlNoErrorContext` | `iris`, `oculus`, `vulkanmod` | `Window` |
| `backend.VulkanRenderPassMixin` | `backendVulkanDispatchCache` | `vulkanmod` | `VulkanRenderPass` |
| `backend.VulkanTextureBindingAccess` | `backendVulkanDispatchCache` | `vulkanmod` | `VulkanRenderPass$TextureViewAndSampler` |
| `backend.VulkanBufferViewMixin` | `backendVulkanBufferViewCache` | `vulkanmod` | `VulkanRenderPass` |
| `backend.VulkanDirectBufferMixin` | `backendVulkanBufferViewCache` | `vulkanmod` | `VulkanGpuBuffer$Direct` |
| `fastwrite.BufferBuilderAccess` | Either dispatch cache | `sodium`, `iris`, `oculus`, `vulkanmod`, `immediatelyfast` | `BufferBuilder` |
| `fastwrite.ByteBufferBuilderAccess` | Either dispatch cache | `sodium`, `iris`, `oculus`, `vulkanmod`, `immediatelyfast` | `ByteBufferBuilder` |
| `fastwrite.BufferBuilderFastWriteMixin` | Either dispatch cache | `sodium`, `iris`, `oculus`, `vulkanmod`, `immediatelyfast` | `BufferBuilder` |
| `fastwrite.ModelPartCubeFastWriteMixin` | Either dispatch cache | `sodium`, `iris`, `oculus`, `vulkanmod`, `immediatelyfast` | `ModelPart$Cube` |
| `fastwrite.QuadParticleFastWriteMixin` | Either dispatch cache | `sodium`, `iris`, `oculus`, `vulkanmod`, `immediatelyfast` | `QuadParticleRenderState` |
| `fastwrite.BakedSheetGlyphAccess` | Either dispatch cache | `sodium`, `iris`, `oculus`, `vulkanmod`, `immediatelyfast` | `BakedSheetGlyph` |
| `fastwrite.GlyphEffectAccess` | Either dispatch cache | `sodium`, `iris`, `oculus`, `vulkanmod`, `immediatelyfast` | `BakedSheetGlyph$EffectInstance` |
| `fastwrite.BakedSheetGlyphFastWriteMixin` | Either dispatch cache | `sodium`, `iris`, `oculus`, `vulkanmod`, `immediatelyfast` | `BakedSheetGlyph` |

“Either dispatch cache” means `backendGlDispatchCache || backendVulkanDispatchCache` at mixin registration. During emission the active vanilla backend selects its own flag when the flags differ. There are no static `init()` hooks for this module. The existing `EphemeraApi` shader service supplies the live external-pack check for GL dispatch.

## State and lifetime

- **OpenGL.** Mip limits are tracked per texture and invalidated when draw setup or command submission observes a disabled cache or an active external shader pack. Draw-buffer state is scoped to a pass and its draw framebuffer. The four-entry framebuffer lookup handles single-color attachment lists and invalidates entries at framebuffer deletion. The `Separate` VAO cache skips the temporary list and map lookup on an identity hit while vanilla still binds the VAO and vertex buffers. A pass with batched dynamic uniform uploads uses vanilla uniform setters for the rest of that pass.
- **Window creation.** The no-error hint is scoped to the vanilla OpenGL window creation call. Minecraft's GL log verbosity must be zero, and synchronous GL logging and render debug labels must be disabled. The hint resets after the call and a failed attempt retries with the normal hint.
- **Native Vulkan.** Pipeline, texture, uniform, vertex, index and scissor state is tracked per pass. Vanilla descriptor validation remains in use in IDE validation mode. Vertex bindings track the first 16 slots. The buffer-view cache owns up to 16 views per live `VulkanGpuBuffer.Direct`. The buffer object supplies the handle identity, and entries match the full-width offset and range and the Vulkan format. Uncached views retain vanilla's deferred destruction. Closing a direct buffer uses vanilla's destruction queue, where Ephemera destroys its views immediately before the buffer's native allocation.
- **Native vertex writers.** The exact vanilla format object, topology, stride, per-element formats, offsets and mask must match. Each baked, particle, glyph or glyph-effect quad reserves one four-vertex span. A vanilla cuboid reserves one span for up to six faces. Input geometry is staged before reservation, and a successful write updates the vertex count, active pointer and element mask. Entity normals preserve the fourth byte, which vanilla leaves alone. The writers support `BLOCK` and `ENTITY` for baked quads and cuboids, `PARTICLE` for particles, and `POSITION_TEX_COLOR`, `POSITION_TEX_LIGHTMAP_COLOR` and `POSITION_COLOR_TEX_LIGHTMAP` for glyphs. Wrapped consumers, other topologies, unknown formats and incomplete builders use vanilla emission.
