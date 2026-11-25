package com.ephemera.mixin.client.culling;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates per-part {@code Object2ObjectArrayMap$ValuesCollection$1} iterator allocations inside
 * {@link ModelPart#render} (~114 MB / 45s in mob/player scenes) by flattening immutable child maps
 * into a compact {@code ModelPart[]} array once on construction. Stands down for Entity Model
 * Features and OptiFabric.
 */
@Mixin(ModelPart.class)
abstract class ModelPartFastChildrenMixin {
    @Unique private static final ModelPart[] EPHEMERA_NO_CHILDREN = new ModelPart[0];

    @Shadow public boolean visible;
    @Shadow public boolean skipDraw;
    @Shadow @Final private List<ModelPart.Cube> cubes;
    @Shadow @Final private Map<String, ModelPart> children;

    @Shadow public abstract void translateAndRotate(PoseStack poseStack);

    @Shadow protected abstract void compile(
            PoseStack.Pose pose, VertexConsumer buffer, int packedLight, int packedOverlay, int color);

    @Unique private ModelPart[] ephemera$childArray;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ephemera$cacheChildren(List<ModelPart.Cube> cubes, Map<String, ModelPart> children, CallbackInfo ci) {
        this.ephemera$childArray = children.isEmpty()
                ? EPHEMERA_NO_CHILDREN
                : children.values().toArray(new ModelPart[0]);
    }

    /**
     * @author Ephemera
     * @reason Flat array iteration over ModelPart children eliminates ~114 MB of ValuesCollection iterators.
     */
    @Overwrite
    public void render(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int color) {
        if (!this.visible) {
            return;
        }
        ModelPart[] childArr = this.ephemera$childArray;
        if (childArr == null || childArr.length != this.children.size()) {
            childArr = this.children.isEmpty()
                    ? EPHEMERA_NO_CHILDREN
                    : this.children.values().toArray(new ModelPart[0]);
            this.ephemera$childArray = childArr;
        }
        if (this.cubes.isEmpty() && childArr.length == 0) {
            return;
        }
        poseStack.pushPose();
        this.translateAndRotate(poseStack);
        if (!this.skipDraw) {
            this.compile(poseStack.last(), buffer, packedLight, packedOverlay, color);
        }
        for (int i = 0, n = childArr.length; i < n; i++) {
            childArr[i].render(poseStack, buffer, packedLight, packedOverlay, color);
        }
        poseStack.popPose();
    }
}
