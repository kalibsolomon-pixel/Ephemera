package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import java.util.*;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastManager.class)
abstract class ToastIdleMixin {
    @Shadow @Final private List<?> visibleToasts;
    @Shadow @Final private Deque<?> queued;
    @Shadow @Final private Set<?> playedToastSounds;
    @Unique private boolean ephemera$hasMusicToast;

    @WrapOperation(method = {"initializeMusicToast", "setMusicToastDisplayState"}, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;nowPlayingToast:Lnet/minecraft/client/gui/components/toasts/ToastManager$ToastInstance;"))
    private void ephemera$trackMusicToast(ToastManager manager, @Coerce Object toast, Operation<Void> original) {
        original.call(manager, toast);
        ephemera$hasMusicToast = toast != null;
    }

    @Inject(method = {"update", "extractRenderState"}, at = @At("HEAD"), cancellable = true)
    private void ephemera$skipEmpty(CallbackInfo ci) {
        if (EphemeraCommon.config().engineIdleShortCircuits && !ephemera$hasMusicToast && visibleToasts.isEmpty()
                && queued.isEmpty() && playedToastSounds.isEmpty()) ci.cancel();
    }
}
