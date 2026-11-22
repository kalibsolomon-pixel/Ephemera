package com.ephemera.render.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.culling.ShadowGroundCacheCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.AABB;

/** Caches only static invisible/full-cube terrain; brightness and entity alpha remain live. */
public final class FastShadowCache {
    private static final ShadowGroundCacheCore CACHE = new ShadowGroundCacheCore(4096);
    private static final BlockPos.MutableBlockPos BELOW = new BlockPos.MutableBlockPos();
    private static final long FULL_CUBE = 1, INVISIBLE = 2, FALLBACK = 3;
    private static final AABB UNIT_BOUNDS = new AABB(0, 0, 0, 1, 1, 1);
    private FastShadowCache() { }
    public static void invalidate() { CACHE.clear(); }
    public static AABB bounds(VoxelShape shape) {
        return EphemeraCommon.config().entityRendererDirectCache && shape == Shapes.block() ? UNIT_BOUNDS : shape.bounds();
    }
    public static AABB cachedFullCubeBounds(VoxelShape shape) {
        return EphemeraCommon.config().entityRendererDirectCache && shape == Shapes.block() ? UNIT_BOUNDS : null;
    }
    public static boolean extract(EntityRenderState state, Level level, float strength,
                                  BlockPos.MutableBlockPos pos, ChunkAccess chunk) {
        if (!EphemeraCommon.config().entityRendererDirectCache || level != Minecraft.getInstance().level
                || !Minecraft.getInstance().isSameThread()) return false;
        int x = pos.getX(), y = pos.getY() - 1, z = pos.getZ();
        long stamp = EntityOcclusionManager.terrainStamp();
        long surface = CACHE.get(x, y, z, stamp);
        if (surface == ShadowGroundCacheCore.MISS) {
            BELOW.set(x, y, z);
            BlockState ground = chunk.getBlockState(BELOW);
            surface = FALLBACK;
            if (!ground.getBlock().hasDynamicShape() && !ground.hasOffsetFunction()) {
                if (ground.getRenderShape() == RenderShape.INVISIBLE) surface = INVISIBLE;
                else if (ground.isCollisionShapeFullBlock(chunk, BELOW) && ground.getShape(chunk, BELOW) == Shapes.block()) surface = FULL_CUBE;
            }
            CACHE.put(x, y, z, stamp, surface);
        }
        if (surface == FALLBACK) return false;
        if (surface == INVISIBLE) return true;
        int light = level.getMaxLocalRawBrightness(pos);
        if (light > 3) {
            float power = strength - (float) (state.y - pos.getY()) * .5f;
            float alpha = Mth.clamp(power * .5f * Lightmap.getBrightness(level.dimensionType(), light), 0, 1);
            state.shadowPieces.add(new EntityRenderState.ShadowPiece((float) (x - state.x),
                    (float) (pos.getY() - state.y), (float) (z - state.z), Shapes.block(), alpha));
        }
        return true;
    }
}
