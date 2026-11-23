package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.client.ClientChunkCacheFastAccess;
import com.ephemera.engine.client.ClientChunkStorageFastLookup;
import com.ephemera.render.terrain.SectionUpdateTrackerFrameCache;
import java.util.Arrays;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Replaces 8 polymorphic {@code Level#getChunk(x, z, ChunkStatus.FULL, false)} calls per uncompiled
 * section inside {@link SectionUpdateTracker#hasAllNeighbors} with direct monomorphic lookups on
 * {@link ClientChunkStorageFastLookup} plus {@link LevelLightEngine#lightOnInColumn(long)}, and
 * memoizes column results across vertical sections within a single {@code LevelExtractor.extract}
 * pass (zero {@code CallbackInfoReturnable} allocation).
 */
@Mixin(SectionUpdateTracker.class)
abstract class SectionUpdateTrackerFastMixin implements SectionUpdateTrackerFrameCache {
	@Unique private static final int EPHEMERA$COL_CACHE_SIZE = 64;

	@Shadow
	private boolean doesChunkExistAt(ClientLevel level, long sectionNode) {
		throw new AssertionError();
	}

	@Unique private boolean ephemera$inExtractFrame;
	@Unique private int ephemera$frameStamp = 1;
	@Unique private final long[] ephemera$colKeys = new long[EPHEMERA$COL_CACHE_SIZE];
	@Unique private final int[] ephemera$colStamps = new int[EPHEMERA$COL_CACHE_SIZE];
	@Unique private final boolean[] ephemera$colReady = new boolean[EPHEMERA$COL_CACHE_SIZE];

	@Override
	public void ephemera$beginExtractFrame() {
		int next = this.ephemera$frameStamp + 1;
		if (next == 0) {
			Arrays.fill(this.ephemera$colStamps, 0);
			next = 1;
		}
		this.ephemera$frameStamp = next;
		this.ephemera$inExtractFrame = true;
	}

	@Override
	public void ephemera$endExtractFrame() {
		this.ephemera$inExtractFrame = false;
	}

	@Unique
	private static boolean ephemera$neighborReady(ClientChunkStorageFastLookup storage, LevelLightEngine lightEngine, int x, int z) {
		return storage.ephemera$getChunkIfValid(x, z) != null
			&& lightEngine.lightOnInColumn(SectionPos.getZeroNode(x, z));
	}

	/**
	 * @author Ephemera
	 * @reason Monomorphic chunk storage + light readiness check with single-frame per-column memoization and zero boxing.
	 */
	@Overwrite
	public boolean hasAllNeighbors(ClientLevel level, long sectionNode) {
		if (!EphemeraCommon.config().engineFastChunkLookup
			|| !(level.getChunkSource() instanceof ClientChunkCacheFastAccess access)) {
			return this.doesChunkExistAt(level, SectionPos.offset(sectionNode, Direction.WEST))
				&& this.doesChunkExistAt(level, SectionPos.offset(sectionNode, Direction.NORTH))
				&& this.doesChunkExistAt(level, SectionPos.offset(sectionNode, Direction.EAST))
				&& this.doesChunkExistAt(level, SectionPos.offset(sectionNode, Direction.SOUTH))
				&& this.doesChunkExistAt(level, SectionPos.offset(sectionNode, -1, 0, -1))
				&& this.doesChunkExistAt(level, SectionPos.offset(sectionNode, -1, 0, 1))
				&& this.doesChunkExistAt(level, SectionPos.offset(sectionNode, 1, 0, -1))
				&& this.doesChunkExistAt(level, SectionPos.offset(sectionNode, 1, 0, 1));
		}
		ClientChunkStorageFastLookup storage = access.ephemera$storageLookup();
		if (storage == null) {
			return false;
		}
		int cx = SectionPos.x(sectionNode);
		int cz = SectionPos.z(sectionNode);
		if (this.ephemera$inExtractFrame) {
			long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
			int slot = (cx & 7) | ((cz & 7) << 3);
			int stamp = this.ephemera$frameStamp;
			if (this.ephemera$colStamps[slot] == stamp && this.ephemera$colKeys[slot] == key) {
				return this.ephemera$colReady[slot];
			}
			LevelLightEngine light = level.getLightEngine();
			boolean ok = ephemera$neighborReady(storage, light, cx - 1, cz)
				&& ephemera$neighborReady(storage, light, cx, cz - 1)
				&& ephemera$neighborReady(storage, light, cx + 1, cz)
				&& ephemera$neighborReady(storage, light, cx, cz + 1)
				&& ephemera$neighborReady(storage, light, cx - 1, cz - 1)
				&& ephemera$neighborReady(storage, light, cx - 1, cz + 1)
				&& ephemera$neighborReady(storage, light, cx + 1, cz - 1)
				&& ephemera$neighborReady(storage, light, cx + 1, cz + 1);
			this.ephemera$colKeys[slot] = key;
			this.ephemera$colStamps[slot] = stamp;
			this.ephemera$colReady[slot] = ok;
			return ok;
		}
		LevelLightEngine light = level.getLightEngine();
		return ephemera$neighborReady(storage, light, cx - 1, cz)
			&& ephemera$neighborReady(storage, light, cx, cz - 1)
			&& ephemera$neighborReady(storage, light, cx + 1, cz)
			&& ephemera$neighborReady(storage, light, cx, cz + 1)
			&& ephemera$neighborReady(storage, light, cx - 1, cz - 1)
			&& ephemera$neighborReady(storage, light, cx - 1, cz + 1)
			&& ephemera$neighborReady(storage, light, cx + 1, cz - 1)
			&& ephemera$neighborReady(storage, light, cx + 1, cz + 1);
	}
}
