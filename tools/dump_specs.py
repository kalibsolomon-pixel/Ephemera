import os
import subprocess
import zipfile
from pathlib import Path

jar = Path(os.path.expanduser(r"~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar"))
out_root = Path(r"C:\Dev\ephemera\build\mc262-specs")
out_root.mkdir(parents=True, exist_ok=True)

with zipfile.ZipFile(jar) as z:
    all_classes = {n[:-6].replace("/", ".") for n in z.namelist() if n.endswith(".class")}
    sh_dir = out_root / "shaders"
    sh_dir.mkdir(exist_ok=True)
    for n in z.namelist():
        if n.startswith("assets/minecraft/shaders/") and (n.endswith(".glsl") or n.endswith(".fsh") or n.endswith(".vsh")):
            rel = n.replace("assets/minecraft/shaders/", "").replace("/", "__")
            (sh_dir / rel).write_bytes(z.read(n))

specs = {
    "terrain": [
        "net.minecraft.client.renderer.LevelRenderer",
        "net.minecraft.client.renderer.chunk.ChunkSectionsToRender",
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher",
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection",
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionUberBuffers",
        "net.minecraft.client.renderer.chunk.SectionCompiler",
        "net.minecraft.client.renderer.chunk.SectionCompiler$Results",
        "net.minecraft.client.renderer.chunk.CompiledSectionMesh",
        "net.minecraft.client.renderer.chunk.SectionMesh",
        "net.minecraft.client.renderer.chunk.RenderSectionRegion",
        "net.minecraft.client.renderer.chunk.SectionCopy",
        "net.minecraft.client.renderer.chunk.VisGraph",
        "net.minecraft.client.renderer.SectionOcclusionGraph",
        "net.minecraft.client.renderer.block.ModelBlockRenderer",
        "net.minecraft.client.renderer.block.FluidRenderer",
        "net.minecraft.client.renderer.block.BlockModelLighter",
        "net.minecraft.client.renderer.texture.TextureAtlas",
        "net.minecraft.client.renderer.texture.SpriteContents",
        "net.minecraft.client.renderer.chunk.ChunkSectionLayer",
        "net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup",
        "com.mojang.blaze3d.vertex.UberGpuBuffer",
        "com.mojang.blaze3d.systems.RenderPass",
        "com.mojang.blaze3d.systems.DeviceFeatures",
        "net.minecraft.client.renderer.DynamicUniforms",
        "net.minecraft.client.renderer.BiomeColors",
    ],
    "backend": [
        "com.mojang.blaze3d.vulkan.VulkanRenderPass",
        "com.mojang.blaze3d.vulkan.VulkanCommandEncoder",
        "com.mojang.blaze3d.vulkan.VulkanDevice",
        "com.mojang.blaze3d.vulkan.VulkanGpuBuffer",
        "com.mojang.blaze3d.vulkan.VulkanRenderPipeline",
        "com.mojang.blaze3d.vulkan.VulkanBindGroupLayout",
        "com.mojang.blaze3d.opengl.GlCommandEncoder",
        "com.mojang.blaze3d.opengl.GlRenderPass",
        "com.mojang.blaze3d.opengl.GlDevice",
        "com.mojang.blaze3d.opengl.GlTexture",
        "com.mojang.blaze3d.opengl.FrameBufferCache",
        "com.mojang.blaze3d.opengl.VertexArrayCache",
        "com.mojang.blaze3d.opengl.VertexArrayCache$Separate",
        "com.mojang.blaze3d.platform.Window",
        "com.mojang.blaze3d.vertex.BufferBuilder",
        "com.mojang.blaze3d.vertex.ByteBufferBuilder",
        "com.mojang.blaze3d.vertex.VertexConsumer",
        "com.mojang.blaze3d.vertex.VertexFormat",
        "com.mojang.blaze3d.vertex.VertexFormatElement",
        "com.mojang.blaze3d.vertex.QuadInstance",
        "net.minecraft.client.resources.model.geometry.BakedQuad",
        "net.minecraft.client.model.geom.ModelPart",
        "net.minecraft.client.model.geom.ModelPart$Cube",
        "net.minecraft.client.model.geom.ModelPart$Polygon",
        "net.minecraft.client.renderer.state.level.QuadParticleRenderState",
        "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph",
        "net.minecraft.client.renderer.StagedVertexBuffer",
    ],
    "culling": [
        "net.minecraft.client.renderer.extract.LevelExtractor",
        "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
        "net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher",
        "net.minecraft.client.renderer.feature.ShadowFeatureRenderer",
        "net.minecraft.client.particle.ParticleEngine",
        "net.minecraft.client.particle.QuadParticleGroup",
        "net.minecraft.client.particle.SingleQuadParticle",
        "net.minecraft.client.multiplayer.ClientLevel",
        "net.minecraft.client.multiplayer.ClientPacketListener",
        "net.minecraft.world.level.block.Block",
        "net.minecraft.world.level.block.LeavesBlock",
        "net.minecraft.client.renderer.blockentity.AbstractSignRenderer",
        "net.minecraft.client.renderer.entity.ItemFrameRenderer",
        "net.minecraft.client.renderer.entity.PaintingRenderer",
        "net.minecraft.client.renderer.WeatherEffectRenderer",
        "net.minecraft.client.renderer.CloudRenderer",
        "net.minecraft.world.entity.EntityType",
        "net.minecraft.world.level.block.entity.BlockEntityType",
    ],
    "gui": [
        "net.minecraft.client.renderer.state.gui.GuiRenderState",
        "net.minecraft.client.renderer.state.gui.GuiRenderState$Node",
        "net.minecraft.client.gui.render.GuiRenderer",
        "net.minecraft.client.gui.render.GuiItemAtlas",
        "net.minecraft.client.gui.render.DynamicAtlasAllocator",
        "net.minecraft.client.gui.GuiGraphicsExtractor",
        "net.minecraft.client.renderer.feature.ItemFeatureRenderer",
        "net.minecraft.client.renderer.item.ItemModelResolver",
        "net.minecraft.client.renderer.item.ItemStackRenderState",
        "net.minecraft.client.resources.language.FormattedBidiReorder",
        "net.minecraft.client.gui.Font",
        "net.minecraft.client.gui.Font$PreparedTextBuilder",
        "net.minecraft.client.gui.font.FontTexture",
        "net.minecraft.client.gui.font.FontSet",
        "net.minecraft.client.gui.font.GlyphStitcher",
        "net.minecraft.client.renderer.texture.TextureManager",
        "com.mojang.blaze3d.platform.FramerateLimitTracker",
    ],
    "engine": [
        "net.minecraft.world.level.block.state.StateHolder",
        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache",
        "net.minecraft.core.component.DataComponentPatch",
        "net.minecraft.world.phys.shapes.VoxelShape",
        "net.minecraft.world.phys.shapes.Shapes",
        "net.minecraft.util.Mth",
        "net.minecraft.world.level.chunk.LevelChunk",
        "net.minecraft.world.level.chunk.LevelChunkSection",
        "net.minecraft.world.level.chunk.PalettedContainer",
        "net.minecraft.world.level.lighting.LightEngine",
        "net.minecraft.world.level.lighting.BlockLightEngine",
        "net.minecraft.world.level.lighting.SkyLightEngine",
        "net.minecraft.world.level.lighting.LayerLightSectionStorage",
        "net.minecraft.world.level.chunk.DataLayer",
        "net.minecraft.network.Connection",
        "net.minecraft.client.renderer.texture.TextureAtlas",
        "net.minecraft.client.renderer.texture.SpriteContents",
        "net.minecraft.client.renderer.Lightmap",
        "net.minecraft.client.renderer.state.LightmapRenderState",
        "net.minecraft.client.gui.components.DebugScreenOverlay",
        "net.minecraft.client.sounds.SoundEngine",
        "com.mojang.blaze3d.audio.Listener",
        "net.minecraft.client.gui.components.toasts.ToastManager",
        "net.minecraft.client.tutorial.Tutorial",
        "net.minecraft.client.gui.screens.LoadingOverlay",
        "net.minecraft.client.gui.screens.TitleScreen",
        "net.minecraft.client.multiplayer.LevelLoadTracker",
        "net.minecraft.client.multiplayer.resolver.ServerNameResolver",
    ],
    "visuals": [
        "net.minecraft.client.renderer.ShaderManager",
        "com.mojang.blaze3d.preprocessor.GlslPreprocessor",
        "net.minecraft.client.renderer.RenderPipelines",
        "com.mojang.blaze3d.systems.SamplerCache",
        "com.mojang.blaze3d.systems.GpuDevice",
        "net.minecraft.client.gui.screens.options.VideoSettingsScreen",
        "net.minecraft.client.gui.screens.options.OptionsScreen",
        "net.minecraft.client.gui.screens.Screen",
        "net.minecraft.client.gui.components.AbstractWidget",
        "net.minecraft.client.gui.components.Button",
        "net.minecraft.client.gui.components.AbstractSliderButton",
        "net.minecraft.client.gui.Gui",
        "net.minecraft.client.gui.GuiGraphicsExtractor",
        "net.minecraft.util.LightCoordsUtil",
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection",
        "net.minecraft.client.renderer.LevelRenderer",
        "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
    ],
}

for domain, classes in specs.items():
    d = out_root / domain
    d.mkdir(exist_ok=True)
    valid = [c for c in classes if c in all_classes]
    missing = [c for c in classes if c not in all_classes]
    for cls in valid:
        fn = cls.split(".")[-1].replace("$", "_") + ".txt"
        p = subprocess.run(["javap", "-cp", str(jar), "-c", "-p", cls], capture_output=True, text=True)
        (d / fn).write_text(p.stdout, encoding="utf-8")
    print(f"{domain}: dumped {len(valid)} classes (missing: {missing})")
