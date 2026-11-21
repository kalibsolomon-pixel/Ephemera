package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.client.ReleasableSprite;
import java.util.List;
import net.minecraft.client.renderer.texture.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
abstract class TextureAtlasMipMixin {
    @Shadow private List<TextureAtlasSprite> sprites;

    @Inject(method = "upload", at = @At("RETURN"))
    private void ephemera$releaseUploadedLevels(CallbackInfo ci) {
        if (EphemeraCommon.config().memoryFreeSpriteMipmaps) {
            for (TextureAtlasSprite sprite : sprites) {
                if (sprite.contents() instanceof ReleasableSprite owned) owned.ephemera$releaseMipmaps();
            }
        }
    }
}
