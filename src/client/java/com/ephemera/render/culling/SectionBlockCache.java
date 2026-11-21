package com.ephemera.render.culling;

import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Sixty-four exact section keys and lazy per-section opacity bitsets persisted across frames while terrainStamp is unchanged. */
final class SectionBlockCache {
    private static final int SLOTS = 64;
    private static final int MASK = SLOTS - 1;
    private final LevelChunkSection[] sections = new LevelChunkSection[SLOTS];
    private final int[] xs = new int[SLOTS], ys = new int[SLOTS], zs = new int[SLOTS];
    private final long[] stamps = new long[SLOTS];
    private final long[] sectionFrames = new long[SLOTS];
    private final long[][] known = new long[SLOTS][64], opaque = new long[SLOTS][64];

    /** Releases MC chunk-section references at frame end without discarding valid opacity bitsets. */
    void releaseSections() {
        Arrays.fill(sections, null);
        Arrays.fill(sectionFrames, 0L);
    }

    /** Full invalidation on terrain revision or world change. */
    void clear() {
        Arrays.fill(sections, null);
        Arrays.fill(sectionFrames, 0L);
        Arrays.fill(stamps, 0L);
    }

    boolean isOpaque(ClientLevel level, int x, int y, int z, long terrainStamp, long frame) {
        if (level.isOutsideBuildHeight(y)) return false;
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        int h = sx * 0x9E3779B1 ^ sy * 0x85EBCA77 ^ sz * 0xC2B2AE3D;
        int slot = (h ^ (h >>> 16)) & MASK;
        int cell = (y & 15) << 8 | (z & 15) << 4 | x & 15;
        int word = cell >>> 6;
        long bit = 1L << cell;
        if (stamps[slot] == terrainStamp && xs[slot] == sx && ys[slot] == sy && zs[slot] == sz) {
            if ((known[slot][word] & bit) != 0L) {
                return (opaque[slot][word] & bit) != 0L;
            }
        } else {
            xs[slot] = sx;
            ys[slot] = sy;
            zs[slot] = sz;
            stamps[slot] = terrainStamp;
            sectionFrames[slot] = 0L;
            sections[slot] = null;
            Arrays.fill(known[slot], 0L);
        }
        LevelChunkSection section;
        if (sectionFrames[slot] == frame) {
            section = sections[slot];
        } else {
            LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
            section = chunk == null ? null : chunk.getSection(level.getSectionIndex(y));
            sections[slot] = section;
            sectionFrames[slot] = frame;
        }
        if (section == null || section.hasOnlyAir()) {
            known[slot][word] |= bit;
            opaque[slot][word] &= ~bit;
            return false;
        }
        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
        boolean solid = state.isSolidRender() && state.canOcclude() && !state.getBlock().hasDynamicShape()
                && !state.hasOffsetFunction();
        known[slot][word] |= bit;
        if (solid) opaque[slot][word] |= bit;
        else opaque[slot][word] &= ~bit;
        return solid;
    }
}
