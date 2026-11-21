package com.ephemera.render.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.culling.LeafCullingPolicy;
import com.ephemera.mixin.client.particle.FrustumAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4fc;

public final class BlockStateCullingManager {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ThreadLocal<BlockPos.MutableBlockPos> LEAF_POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private BlockStateCullingManager() { }

    /** Each shell must have six completely opaque neighbors; cutout leaf depth is never sufficient. */
    public static boolean enclosedLeaf(BlockAndTintGetter world, BlockPos pos, BlockState state) {
        EphemeraConfig cfg = EphemeraCommon.config();
        if (!cfg.blockStateCulling || cfg.leavesCullingMode == null || cfg.leavesCullingMode == EphemeraConfig.LeavesCullingMode.OFF
                || !(state.getBlock() instanceof LeavesBlock) || state.hasOffsetFunction()) return false;
        LeafCullingPolicy.Mode mode = switch (cfg.leavesCullingMode) {
            case OFF -> LeafCullingPolicy.Mode.OFF;
            case CHECK_SURROUNDING -> LeafCullingPolicy.Mode.CHECK_SURROUNDING;
            case DEPTH -> LeafCullingPolicy.Mode.DEPTH;
            case FAST_OPAQUE -> LeafCullingPolicy.Mode.FAST_OPAQUE;
        };
        if (mode == LeafCullingPolicy.Mode.FAST_OPAQUE) return false; // The pairwise fast path is handled by LeavesBlock.
        int depth = mode == LeafCullingPolicy.Mode.DEPTH ? Math.max(1, Math.min(8, cfg.leavesCullingDepth)) : 1;
        BlockPos.MutableBlockPos scratch = LEAF_POS.get();
        int proven = 0;
        for (int distance = 1; distance <= depth; distance++) {
            int mask = 0;
            for (Direction direction : DIRECTIONS) {
                scratch.set(pos.getX() + direction.getStepX() * distance,
                        pos.getY() + direction.getStepY() * distance, pos.getZ() + direction.getStepZ() * distance);
                BlockState neighbor = world.getBlockState(scratch);
                if (neighbor.isSolidRender() && neighbor.canOcclude() && !neighbor.getBlock().hasDynamicShape()
                        && !neighbor.hasOffsetFunction()) mask |= 1 << direction.get3DDataValue();
            }
            if (mask != 63) return false;
            proven++;
        }
        return LeafCullingPolicy.skip(mode, false, false, 63, proven, depth);
    }
    public static boolean opaqueLeafFace(boolean cutout, BlockState neighbor) {
        EphemeraConfig cfg = EphemeraCommon.config();
        return cfg.blockStateCulling && cfg.leavesCullingMode == EphemeraConfig.LeavesCullingMode.FAST_OPAQUE
                && LeafCullingPolicy.skip(LeafCullingPolicy.Mode.FAST_OPAQUE, !cutout,
                neighbor.getBlock() instanceof LeavesBlock, 0, 0, 1);
    }
    public static boolean textFacingAway(Matrix4fc pose) {
        if (!EphemeraCommon.config().blockStateCulling || !EphemeraCommon.config().signTextBackfaceCulling) return false;
        double nx = pose.m20(), ny = pose.m21(), nz = pose.m22();
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double dot = -pose.m30() * nx - pose.m31() * ny - pose.m32() * nz;
        return Double.isFinite(dot) && length > 0 && dot < -.125 * length;
    }
    public static boolean decorationHidden(Entity entity) {
        EphemeraConfig cfg = EphemeraCommon.config();
        if (!cfg.blockStateCulling || EntityOcclusionManager.excluded(entity)) return false;
        Direction facing;
        if (entity instanceof ItemFrame frame && cfg.itemFrameCulling) facing = frame.getDirection();
        else if (entity instanceof Painting painting && cfg.paintingBackfaceCulling) facing = painting.getDirection();
        else return false;
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        // A decoration has a real back and edges. Back-facing alone is never enough to hide it.
        double dot = (camera.position().x - entity.getX()) * facing.getStepX()
                + (camera.position().y - entity.getY()) * facing.getStepY()
                + (camera.position().z - entity.getZ()) * facing.getStepZ();
        if (dot >= -.25) return false;
        AABB box = entity.getBoundingBox();
        return EntityOcclusionManager.boxOccluded(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }
    public static boolean weatherHidden(int x, int bottom, int top, int z) {
        if (!EphemeraCommon.config().blockStateCulling || !EphemeraCommon.config().weatherCulling) return false;
        var frustum = EntityOcclusionManager.frustum();
        if (frustum != null) {
            int result = ((FrustumAccessor) frustum).ephemera$cube(x - 1, bottom - 1, z - 1, x + 2, top + 1, z + 2);
            if (result != -1 && result != -2) return true;
        }
        return EntityOcclusionManager.boxOccluded(x - 1, bottom - 1, z - 1, x + 2, top + 1, z + 2);
    }
    public static Direction[] cloudDirections() {
        return EphemeraCommon.config().blockStateCulling && EphemeraCommon.config().cloudFaceCulling ? DIRECTIONS : Direction.values();
    }
}
