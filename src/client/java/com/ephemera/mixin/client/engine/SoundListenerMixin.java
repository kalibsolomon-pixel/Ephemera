package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import net.minecraft.client.Camera;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
abstract class SoundListenerMixin {
    @Unique private Vec3 ephemera$position;
    @Unique private final Vector3f ephemera$forward = new Vector3f();
    @Unique private final Vector3f ephemera$up = new Vector3f();

    @Inject(method = "updateSource", at = @At(value = "NEW", target = "com/mojang/blaze3d/audio/ListenerTransform"), cancellable = true)
    private void ephemera$checkTransform(Camera camera, CallbackInfo ci) {
        if (!EphemeraCommon.config().engineSoundListenerThrottle) {
            ephemera$position = null;
            return;
        }
        var position = camera.position();
        var forward = camera.forwardVector();
        var up = camera.upVector();
        if (position.equals(ephemera$position)
                && ephemera$forward.equals(forward.x(), forward.y(), forward.z())
                && ephemera$up.equals(up.x(), up.y(), up.z())) {
            ci.cancel();
            return;
        }
        ephemera$position = position;
        ephemera$forward.set(forward);
        ephemera$up.set(up);
    }

    @Inject(method = {"loadLibrary", "stopAll", "destroy"}, at = @At("HEAD"))
    private void ephemera$resetTransform(CallbackInfo ci) {
        ephemera$position = null;
    }
}
