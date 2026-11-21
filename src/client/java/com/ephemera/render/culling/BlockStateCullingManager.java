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

    /** Whole-block leaf culling for CHECK_SURROUNDING (all 6 neighbors opaque solid). */
    public static boolean enclosedLeaf(BlockAndTintGetter world, BlockPos pos, BlockState state) {
        EphemeraConfig cfg = EphemeraCommon.config();
        if (!cfg.blockStateCulling || cfg.leavesCullingMode == null || cfg.leavesCullingMode == EphemeraConfig.LeavesCullingMode.OFF
                || !(state.getBlock() instanceof LeavesBlock) || state.hasOffsetFunction()) return false;
        LeafCullingPolicy.Mode mode = toPolicyMode(cfg.leavesCullingMode);
        if (mode == LeafCullingPolicy.Mode.FAST_OPAQUE) return false;
        if (mode == LeafCullingPolicy.Mode.DEPTH) {
            // Fast whole-block skip only when all 6 faces are covered to the configured depth
            // (saves per-face model lookup for deeply buried canopy cores).
            int depth = LeafCullingPolicy.clampDepth(cfg.leavesCullingDepth);
            BlockPos.MutableBlockPos scratch = LEAF_POS.get();
            for (Direction direction : DIRECTIONS) {
                if (!leafDirectionCovered(world, pos.getX(), pos.getY(), pos.getZ(), direction, depth, scratch)) {
                    return false;
                }
            }
            return true;
        }
        BlockPos.MutableBlockPos scratch = LEAF_POS.get();
        int mask = 0;
        for (Direction direction : DIRECTIONS) {
            scratch.set(pos.getX() + direction.getStepX(),
                    pos.getY() + direction.getStepY(), pos.getZ() + direction.getStepZ());
            BlockState neighbor = world.getBlockState(scratch);
            if (isFullOpaqueBlock(neighbor)) mask |= 1 << direction.get3DDataValue();
        }
        return LeafCullingPolicy.skip(mode, false, false, mask, 1, 1);
    }

    /**
     * Per-face leaf depth culling called from {@code ModelBlockRenderer.shouldRenderFace}: skips
     * an interior leaf quad when {@code leavesCullingDepth} consecutive blocks in {@code direction}
     * are continuous leaves (or terminate in an opaque solid block), keeping outer tree shells lush.
     */
    public static boolean cullLeafFace(BlockAndTintGetter world, BlockState state, Direction direction, BlockPos neighborPos) {
        EphemeraConfig cfg = EphemeraCommon.config();
        if (!cfg.blockStateCulling || cfg.leavesCullingMode != EphemeraConfig.LeavesCullingMode.DEPTH
                || !(state.getBlock() instanceof LeavesBlock) || state.hasOffsetFunction()) {
            return false;
        }
        int depth = LeafCullingPolicy.clampDepth(cfg.leavesCullingDepth);
        BlockPos.MutableBlockPos scratch = LEAF_POS.get();
        int nx = neighborPos.getX(), ny = neighborPos.getY(), nz = neighborPos.getZ();
        int sx = direction.getStepX(), sy = direction.getStepY(), sz = direction.getStepZ();
        int cover = 0;
        for (int i = 0; i < depth; i++) {
            scratch.set(nx + sx * i, ny + sy * i, nz + sz * i);
            BlockState ahead = world.getBlockState(scratch);
            if (isFullOpaqueBlock(ahead)) {
                return LeafCullingPolicy.skipFace(LeafCullingPolicy.Mode.DEPTH, true, cover, depth);
            }
            if (!(ahead.getBlock() instanceof LeavesBlock) || ahead.hasOffsetFunction()) {
                return false;
            }
            cover++;
        }
        return LeafCullingPolicy.skipFace(LeafCullingPolicy.Mode.DEPTH, false, cover, depth);
    }

    private static boolean leafDirectionCovered(BlockAndTintGetter world, int x, int y, int z,
                                                Direction direction, int depth, BlockPos.MutableBlockPos scratch) {
        int sx = direction.getStepX(), sy = direction.getStepY(), sz = direction.getStepZ();
        for (int d = 1; d <= depth; d++) {
            scratch.set(x + sx * d, y + sy * d, z + sz * d);
            BlockState ahead = world.getBlockState(scratch);
            if (isFullOpaqueBlock(ahead)) return true;
            if (!(ahead.getBlock() instanceof LeavesBlock) || ahead.hasOffsetFunction()) return false;
        }
        return true;
    }

    private static boolean isFullOpaqueBlock(BlockState state) {
        return state.isSolidRender() && state.canOcclude()
                && !state.getBlock().hasDynamicShape() && !state.hasOffsetFunction();
    }

    private static LeafCullingPolicy.Mode toPolicyMode(EphemeraConfig.LeavesCullingMode mode) {
        return switch (mode) {
            case OFF -> LeafCullingPolicy.Mode.OFF;
            case CHECK_SURROUNDING -> LeafCullingPolicy.Mode.CHECK_SURROUNDING;
            case DEPTH -> LeafCullingPolicy.Mode.DEPTH;
            case FAST_OPAQUE -> LeafCullingPolicy.Mode.FAST_OPAQUE;
        };
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
