package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.engine.FastGizmoCollector;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import net.minecraft.gizmos.SimpleGizmoCollector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Avoids allocating a fresh {@code ArrayList} and lambda {@code Predicate} in
 * {@link SimpleGizmoCollector#drainGizmos()} when no gizmos are queued.
 */
@Mixin(SimpleGizmoCollector.class)
abstract class SimpleGizmoCollectorFastMixin implements FastGizmoCollector {
    @Shadow @Final private List<SimpleGizmoCollector.GizmoInstance> gizmos;
    @Shadow @Final private List<SimpleGizmoCollector.GizmoInstance> temporaryGizmos;

    @Override
    public boolean ephemera$isEmpty() {
        return this.gizmos.isEmpty() && this.temporaryGizmos.isEmpty();
    }

    @WrapMethod(method = "drainGizmos")
    private List<SimpleGizmoCollector.GizmoInstance> ephemera$fastDrain(
            Operation<List<SimpleGizmoCollector.GizmoInstance>> original) {
        if (EphemeraCommon.config().engineIdleShortCircuits
                && this.gizmos.isEmpty() && this.temporaryGizmos.isEmpty()) {
            return List.of();
        }
        return original.call();
    }
}
