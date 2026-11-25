package com.ephemera.render.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.culling.ShadowGroundCacheCore;
import java.util.Arrays;
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

/**
 * Caches static invisible/full-cube ground blocks and reuses bit-exact immutable
 * {@link EntityRenderState.ShadowPiece} records across frames between 20 Hz ticks.
 */
public final class FastShadowCache {
    private static final ShadowGroundCacheCore CACHE = new ShadowGroundCacheCore(4096);
    private static final int PIECE_SLOTS = 2048;
    private static final int PIECE_MASK = PIECE_SLOTS - 1;
    private static final int[] PIECE_RX = new int[PIECE_SLOTS];
    private static final int[] PIECE_RY = new int[PIECE_SLOTS];
    private static final int[] PIECE_RZ = new int[PIECE_SLOTS];
    private static final int[] PIECE_ALPHA = new int[PIECE_SLOTS];
    private static final EntityRenderState.ShadowPiece[] PIECE_VAL = new EntityRenderState.ShadowPiece[PIECE_SLOTS];
    private static final BlockPos.MutableBlockPos BELOW = new BlockPos.MutableBlockPos();
    private static final long FULL_CUBE = 1, INVISIBLE = 2, FALLBACK = 3;
    private static final AABB UNIT_BOUNDS = new AABB(0, 0, 0, 1, 1, 1);

    private FastShadowCache() { }

    public static void invalidate() {
        CACHE.clear();
        Arrays.fill(PIECE_VAL, null);
    }

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
            float rx = (float) (x - state.x);
            float ry = (float) (pos.getY() - state.y);
            float rz = (float) (z - state.z);
            int ix = Float.floatToRawIntBits(rx);
            int iy = Float.floatToRawIntBits(ry);
            int iz = Float.floatToRawIntBits(rz);
            int ia = Float.floatToRawIntBits(alpha);
            int h = ix * 0x9E3779B1 ^ iy * 0x85EBCA77 ^ iz * 0xC2B2AE3D ^ ia * 0x27D4EB2D;
            int slot = (h ^ (h >>> 16)) & PIECE_MASK;
            EntityRenderState.ShadowPiece piece = PIECE_VAL[slot];
            if (PIECE_RX[slot] == ix && PIECE_RY[slot] == iy && PIECE_RZ[slot] == iz && PIECE_ALPHA[slot] == ia) {
                if (piece == null) {
                    // 2-hit promotion filter: only retain ShadowPiece in the long-lived array once
                    // seen twice unchanged (stationary entity between 20 Hz ticks). Moving mobs stay
                    // purely young-gen and never pollute G1 old-gen remembered sets.
                    piece = new EntityRenderState.ShadowPiece(rx, ry, rz, Shapes.block(), alpha);
                    PIECE_VAL[slot] = piece;
                }
            } else {
                PIECE_RX[slot] = ix;
                PIECE_RY[slot] = iy;
                PIECE_RZ[slot] = iz;
                PIECE_ALPHA[slot] = ia;
                PIECE_VAL[slot] = null;
                piece = new EntityRenderState.ShadowPiece(rx, ry, rz, Shapes.block(), alpha);
            }
            state.shadowPieces.add(piece);
        }
        return true;
    }
}
