package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.lighting.FastLightQueue;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LightEngine.class)
abstract class LightWorkMixin {
    @Shadow @Final private LongArrayFIFOQueue increaseQueue;
    @Unique private final FastLightQueue ephemera$up = EphemeraCommon.config().engineFastLighting ? new FastLightQueue() : null;
    @Unique private final FastLightQueue ephemera$down = ephemera$up == null ? null : new FastLightQueue();

    @Unique private FastLightQueue ephemera$queue(LongArrayFIFOQueue queue) {
        return queue == increaseQueue ? ephemera$up : ephemera$down;
    }

    @Redirect(method = {"enqueueIncrease", "enqueueDecrease"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongArrayFIFOQueue;enqueue(J)V"))
    private void ephemera$enqueue(LongArrayFIFOQueue queue, long word) {
        if (ephemera$up == null) queue.enqueue(word);
        else ephemera$queue(queue).add(word);
    }

    @Redirect(method = {"propagateIncreases", "propagateDecreases"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongArrayFIFOQueue;dequeueLong()J"))
    private long ephemera$dequeue(LongArrayFIFOQueue queue) {
        return ephemera$up == null ? queue.dequeueLong() : ephemera$queue(queue).remove();
    }

    @Redirect(method = {"propagateIncreases", "propagateDecreases", "hasLightWork"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongArrayFIFOQueue;isEmpty()Z"))
    private boolean ephemera$isEmpty(LongArrayFIFOQueue queue) {
        return ephemera$up == null ? queue.isEmpty() : ephemera$queue(queue).isEmpty();
    }

    @ModifyConstant(method = "runLightUpdates", constant = @Constant(intValue = 512))
    private int ephemera$retainSmallBursts(int original) {
        return EphemeraCommon.config().engineFastLighting ? 2048 : original;
    }
}
