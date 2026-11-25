package com.ephemera.render.culling;

import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** 256 exact section keys and lazy per-section opacity bitsets persisted across frames while terrainStamp is unchanged. */
final class SectionBlockCache {
    private static final int SLOTS = 256;
    private static final int MASK = SLOTS - 1;
    private final LevelChunkSection[] sections = new LevelChunkSection[SLOTS];
    private final int[] xs = new int[SLOTS], ys = new int[SLOTS], zs = new int[SLOTS];
    private final long[] stamps = new long[SLOTS];
    private final long[] sectionFrames = new long[SLOTS];
    private final long[][] known = new long[SLOTS][64], opaque = new long[SLOTS][64];
    // Per-slot 64-bit validity mask over the known/opaque words: one store replaces the two
    // 512-byte Arrays.fill sweeps on slot eviction (wave 6).
    private final long[] knownWordMasks = new long[SLOTS];
    private boolean hasSectionRefs;

    /** Releases MC chunk-section references at frame end without discarding valid opacity bitsets. */
    void releaseSections() {
        if (!hasSectionRefs) {
            return;
        }
        hasSectionRefs = false;
        Arrays.fill(sections, null);
        Arrays.fill(sectionFrames, 0L);
    }

    /** Full invalidation on terrain revision or world change. */
    void clear() {
        hasSectionRefs = false;
        Arrays.fill(sections, null);
        Arrays.fill(sectionFrames, 0L);
        Arrays.fill(stamps, 0L);
    }

    /** Targeted invalidation of one 16x16x16 section after a block opacity change. */
    void invalidateSection(int sx, int sy, int sz) {
        int h = sx * 0x9E3779B1 ^ sy * 0x85EBCA77 ^ sz * 0xC2B2AE3D;
        int slot = (h ^ (h >>> 16)) & MASK;
        if (stamps[slot] != 0L && xs[slot] == sx && ys[slot] == sy && zs[slot] == sz) {
            stamps[slot] = 0L;
            sectionFrames[slot] = 0L;
            sections[slot] = null;
            knownWordMasks[slot] = 0L;
        }
    }

    /** Targeted invalidation of one chunk column after chunk load or unload. */
    void invalidateColumn(int sx, int sz) {
        for (int i = 0; i < SLOTS; i++) {
            if (stamps[i] != 0L && xs[i] == sx && zs[i] == sz) {
                stamps[i] = 0L;
                sectionFrames[i] = 0L;
                sections[i] = null;
                knownWordMasks[i] = 0L;
            }
        }
    }

    boolean isOpaque(ClientLevel level, int x, int y, int z, long terrainStamp, long frame) {
        if (level.isOutsideBuildHeight(y)) return false;
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        int h = sx * 0x9E3779B1 ^ sy * 0x85EBCA77 ^ sz * 0xC2B2AE3D;
        int slot = (h ^ (h >>> 16)) & MASK;
        int cell = (y & 15) << 8 | (z & 15) << 4 | x & 15;
        int word = cell >>> 6;
        long bit = 1L << cell;
        if (stamps[slot] == terrainStamp && xs[slot] == sx && ys[slot] == sy && zs[slot] == sz
                && (knownWordMasks[slot] & (1L << word)) != 0L) {
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
            // Recycled slot: every word holds the previous section's data. Leave the stale bytes
            // in place and mark all words invalid - the validity mask gates every read.
            knownWordMasks[slot] = 0L;
        }
        LevelChunkSection section;
        if (sectionFrames[slot] == frame) {
            section = sections[slot];
        } else {
            LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
            section = chunk == null ? null : chunk.getSection(level.getSectionIndex(y));
            sections[slot] = section;
            sectionFrames[slot] = frame;
            hasSectionRefs = true;
        }
        if (section == null || section.hasOnlyAir()) {
            knownWordMasks[slot] |= 1L << word;
            known[slot][word] |= bit;
            opaque[slot][word] &= ~bit;
            return false;
        }
        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
        boolean solid = state.isSolidRender() && state.canOcclude() && !state.getBlock().hasDynamicShape()
                && !state.hasOffsetFunction();
        knownWordMasks[slot] |= 1L << word;
        known[slot][word] |= bit;
        if (solid) opaque[slot][word] |= bit;
        else opaque[slot][word] &= ~bit;
        return solid;
    }
}
