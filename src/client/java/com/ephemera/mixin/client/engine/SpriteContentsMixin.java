package com.ephemera.mixin.client.engine;

import com.ephemera.engine.client.ReleasableSprite;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(SpriteContents.class)
abstract class SpriteContentsMixin implements ReleasableSprite {
    @Shadow private NativeImage[] byMipLevel;
    @Shadow public abstract void increaseMipLevel(int level);
    @Unique private int ephemera$releasedLevel;

    @Override
    public void ephemera$releaseMipmaps() {
        NativeImage[] levels = byMipLevel;
        if (levels.length < 2 || getClass() != (Class<?>) SpriteContents.class) return;
        byMipLevel = new NativeImage[]{levels[0]};
        ephemera$releasedLevel = levels.length - 1;
        for (int i = 1; i < levels.length; i++) levels[i].close();
    }

    @Unique private void ephemera$restoreLevels() {
        if (ephemera$releasedLevel >= byMipLevel.length) increaseMipLevel(ephemera$releasedLevel);
    }

    @Inject(method = "uploadFirstFrame", at = @At("HEAD"))
    private void ephemera$restoreForUpload(GpuTexture texture, int level, CallbackInfo ci) {
        if (level >= byMipLevel.length) ephemera$restoreLevels();
    }

    @Inject(method = "createAnimationState", at = @At("HEAD"))
    private void ephemera$restoreForAnimation(GpuBufferSlice slice, int index, CallbackInfoReturnable<?> ci) {
        ephemera$restoreLevels();
    }
}
