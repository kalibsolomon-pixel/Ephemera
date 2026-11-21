import os
import subprocess
import zipfile
from pathlib import Path

jar = Path(os.path.expanduser(r"~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar"))
out = Path(r"C:\Dev\ephemera\build\mc262-specs")

with zipfile.ZipFile(jar) as z:
    all_c = sorted(n[:-6].replace("/", ".") for n in z.namelist() if n.endswith(".class"))

extras = {
    "terrain": [c for c in all_c if c.startswith((
        "net.minecraft.client.renderer.texture.TextureAtlasSprite",
        "net.minecraft.client.renderer.texture.SpriteContents",
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$",
        "net.minecraft.client.renderer.chunk.SectionMesh$",
        "com.mojang.blaze3d.vertex.UberGpuBuffer$",
        "com.mojang.blaze3d.vertex.TlsfAllocator",
    ))],
    "gui": [c for c in all_c if c.startswith((
        "net.minecraft.client.renderer.item.ItemStackRenderState$",
        "net.minecraft.client.gui.render.GuiItemAtlas$",
        "net.minecraft.client.gui.render.GuiRenderer$",
        "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph",
    ))],
    "culling": [c for c in all_c if c.startswith((
        "net.minecraft.client.renderer.feature.ShadowFeatureRenderer$",
        "net.minecraft.client.renderer.extract.LevelExtractor$",
        "net.minecraft.client.renderer.state.level.EntityRenderState",
        "net.minecraft.client.renderer.state.level.LevelRenderState",
    ))],
    "backend": [c for c in all_c if c.startswith((
        "com.mojang.blaze3d.opengl.GlRenderPass$",
        "com.mojang.blaze3d.vulkan.VulkanRenderPass$",
        "com.mojang.blaze3d.vulkan.VulkanBindGroupLayout$",
        "com.mojang.blaze3d.vertex.DefaultVertexFormat",
        "com.mojang.blaze3d.opengl.GlBuffer",
        "com.mojang.blaze3d.opengl.GlStateManager",
    ))],
}

for dom, cls_list in extras.items():
    d = out / dom
    d.mkdir(parents=True, exist_ok=True)
    for c in cls_list:
        fn = c.split(".")[-1].replace("$", "_") + ".txt"
        if not (d / fn).exists():
            p = subprocess.run(["javap", "-cp", str(jar), "-c", "-p", c], capture_output=True, text=True)
            (d / fn).write_text(p.stdout, encoding="utf-8")

lines = []
for sub in sorted(p for p in out.iterdir() if p.is_dir()):
    lines.append(f"=== {sub.name} ===")
    for f in sorted(sub.iterdir()):
        lines.append(str(f))
(out / "INDEX.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"Total spec files indexed: {len(lines)}")
