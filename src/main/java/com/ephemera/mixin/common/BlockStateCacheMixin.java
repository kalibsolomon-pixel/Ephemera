package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.*;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
abstract class BlockStateCacheMixin implements StateCacheView {
    @Shadow @Final @Mutable private boolean[] faceSturdy;
    @Shadow @Final @Mutable public VoxelShape collisionShape;
    @Shadow @Final public boolean largeCollisionShape;
    @Shadow @Final public boolean isCollisionShapeFullBlock;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ephemera$shareFields(CallbackInfo ci) {
        var cfg = EphemeraCommon.config();
        if (cfg.memoryCompactBlockStates) faceSturdy = StateInterning.supports(faceSturdy);
        if (cfg.memoryDeduplicateShapes) collisionShape = StateInterning.shape(collisionShape);
    }

    @Override
    public StateCacheKey ephemera$key() {
        return new StateCacheKey(collisionShape, faceSturdy, largeCollisionShape, isCollisionShapeFullBlock);
    }
}
