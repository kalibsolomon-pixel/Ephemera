package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.common.FastChunkLookup;
import com.ephemera.engine.common.FluidTrackerAccess;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates per-entity-tick iterator/lambda churn, {@code MutableBlockPos} allocations,
 * duplicate chunk lookups, and tag-entry scans in {@link EntityFluidInteraction#update}.
 */
@Mixin(EntityFluidInteraction.class)
abstract class EntityFluidInteractionFastMixin {
	@Shadow @Final private Map<TagKey<Fluid>, ?> trackerByFluid;

	@Unique
	private static final ThreadLocal<BlockPos.MutableBlockPos> EPHEMERA$POS =
		ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

	@Unique
	private static final @Nullable MethodHandle EPHEMERA$ACCUMULATE = ephemera$resolveAccumulate();

	@Unique private @Nullable FluidTrackerAccess[] ephemera$allTrackers;
	@Unique private @Nullable Object ephemera$waterTracker;
	@Unique private @Nullable Object ephemera$lavaTracker;
	@Unique private boolean ephemera$cacheReady;

	@Unique
	private static @Nullable MethodHandle ephemera$resolveAccumulate() {
		try {
			Class<?> cls = Class.forName("net.minecraft.world.entity.EntityFluidInteraction$Tracker");
			return MethodHandles.lookup()
				.findVirtual(cls, "accumulateCurrent", MethodType.methodType(void.class, Vec3.class))
				.asType(MethodType.methodType(void.class, Object.class, Vec3.class));
		} catch (Throwable ignored) {
			return null;
		}
	}

	@Unique
	private void ephemera$initCache() {
		int size = this.trackerByFluid.size();
		FluidTrackerAccess[] arr = new FluidTrackerAccess[size];
		int idx = 0;
		for (Map.Entry<TagKey<Fluid>, ?> e : this.trackerByFluid.entrySet()) {
			Object v = e.getValue();
			if (!(v instanceof FluidTrackerAccess acc)) {
				this.ephemera$allTrackers = null;
				this.ephemera$cacheReady = true;
				return;
			}
			arr[idx++] = acc;
			TagKey<Fluid> key = e.getKey();
			if (key == FluidTags.WATER) {
				this.ephemera$waterTracker = v;
			} else if (key == FluidTags.LAVA) {
				this.ephemera$lavaTracker = v;
			}
		}
		this.ephemera$allTrackers = arr;
		this.ephemera$cacheReady = true;
	}

	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastUpdate(Entity entity, boolean ignoreCurrent, CallbackInfo ci) {
		if (!EphemeraCommon.config().engineFastChunkLookup || EPHEMERA$ACCUMULATE == null) {
			return;
		}
		if (!this.ephemera$cacheReady) {
			ephemera$initCache();
		}
		FluidTrackerAccess[] trackers = this.ephemera$allTrackers;
		if (trackers == null) {
			return;
		}
		for (int i = 0; i < trackers.length; i++) {
			trackers[i].ephemera$reset();
		}

		AABB box = entity.getFluidInteractionBox();
		if (box == null) {
			ci.cancel();
			return;
		}
		Level level = entity.level();
		if (level.isDebug()) {
			return;
		}

		int x0 = Mth.floor(box.minX);
		int y0 = Mth.floor(box.minY);
		int z0 = Mth.floor(box.minZ);
		int x1 = Mth.ceil(box.maxX) - 1;
		int y1 = Mth.ceil(box.maxY) - 1;
		int z1 = Mth.ceil(box.maxZ) - 1;

		int minLevelY = level.getMinY();
		int minSecY = minLevelY >> 4;
		int sy0 = (y0 >> 4) - minSecY;
		int sy1 = (y1 >> 4) - minSecY;

		int cxPad0 = (x0 - 1) >> 4;
		int cxPad1 = (x1 + 1) >> 4;
		int czPad0 = (z0 - 1) >> 4;
		int czPad1 = (z1 + 1) >> 4;

		FastChunkLookup fast = level instanceof FastChunkLookup f ? f : null;

		// Fast path: entire padded box (x0-1..x1+1, z0-1..z1+1) resides in ONE single chunk (~80%+ of entities).
		LevelChunk singleChunk = null;
		LevelChunkSection[] singleSections = null;
		if (cxPad0 == cxPad1 && czPad0 == czPad1) {
			singleChunk = ephemera$getChunk(level, fast, cxPad0, czPad0);
			if (singleChunk == null) {
				ci.cancel();
				return;
			}
			singleSections = singleChunk.getSections();
			boolean hasFluid = false;
			int hi = Math.min(sy1, singleSections.length - 1);
			for (int sy = Math.max(0, sy0); sy <= hi; sy++) {
				LevelChunkSection sec = singleSections[sy];
				if (sec != null && sec.hasFluid()) {
					hasFluid = true;
					break;
				}
			}
			if (!hasFluid) {
				ci.cancel();
				return;
			}
		} else {
			boolean hasFluid = false;
			for (int cz = czPad0; cz <= czPad1; cz++) {
				for (int cx = cxPad0; cx <= cxPad1; cx++) {
					LevelChunk lc = ephemera$getChunk(level, fast, cx, cz);
					if (lc == null) {
						ci.cancel();
						return;
					}
					if (!hasFluid) {
						LevelChunkSection[] secs = lc.getSections();
						int hi = Math.min(sy1, secs.length - 1);
						for (int sy = Math.max(0, sy0); sy <= hi; sy++) {
							LevelChunkSection sec = secs[sy];
							if (sec != null && sec.hasFluid()) {
								hasFluid = true;
								break;
							}
						}
					}
				}
			}
			if (!hasFluid) {
				ci.cancel();
				return;
			}
		}

		// At least one section has fluid and all padded chunks are loaded: scan fluid cells directly.
		double bbMinY = entity.getBoundingBox().minY;
		int eyeBlockX = entity.getBlockX();
		double eyeY = entity.getEyeY();
		int eyeBlockZ = entity.getBlockZ();
		int maxLevelY = level.getMaxY();

		Fluid lastFluid = null;
		Object lastTrackerObj = null;
		BlockPos.MutableBlockPos mpos = EPHEMERA$POS.get();
		int cachedCx = singleChunk != null ? cxPad0 : Integer.MIN_VALUE;
		int cachedCz = singleChunk != null ? czPad0 : Integer.MIN_VALUE;
		LevelChunkSection[] cachedSections = singleSections;

		for (int x = x0; x <= x1; x++) {
			int cx = x >> 4;
			int lx = x & 15;
			for (int y = y0; y <= y1; y++) {
				if (y < minLevelY || y >= maxLevelY) {
					continue;
				}
				int secIdx = (y >> 4) - minSecY;
				int ly = y & 15;
				for (int z = z0; z <= z1; z++) {
					int cz = z >> 4;
					if (cx != cachedCx || cz != cachedCz) {
						LevelChunk lc = ephemera$getChunk(level, fast, cx, cz);
						cachedCx = cx;
						cachedCz = cz;
						cachedSections = lc != null ? lc.getSections() : null;
					}
					if (cachedSections == null || secIdx < 0 || secIdx >= cachedSections.length) {
						continue;
					}
					LevelChunkSection sec = cachedSections[secIdx];
					if (sec == null || !sec.hasFluid()) {
						continue;
					}
					FluidState fs = sec.getBlockState(lx, ly, z & 15).getFluidState();
					if (fs.isEmpty()) {
						continue;
					}
					mpos.set(x, y, z);
					double surfaceY = (double) y + (double) fs.getHeight(level, mpos);
					if (surfaceY < box.minY) {
						continue;
					}
					Fluid fluidType = fs.getType();
					if (fluidType != lastFluid) {
						lastFluid = fluidType;
						lastTrackerObj = ephemera$lookupTracker(fluidType);
					}
					if (lastTrackerObj == null) {
						continue;
					}
					FluidTrackerAccess acc = (FluidTrackerAccess) lastTrackerObj;
					if (x == eyeBlockX && z == eyeBlockZ && eyeY >= (double) y && eyeY <= surfaceY) {
						acc.ephemera$setEyesInside(true);
					}
					double newH = Math.max(surfaceY - bbMinY, acc.ephemera$height());
					acc.ephemera$setHeight(newH);
					if (!ignoreCurrent) {
						Vec3 flow = fs.getFlow(level, mpos);
						if (newH < 0.4) {
							flow = flow.scale(newH);
						}
						try {
							EPHEMERA$ACCUMULATE.invokeExact(lastTrackerObj, flow);
						} catch (Throwable ignored) {
						}
					}
				}
			}
		}
		ci.cancel();
	}

	@Unique
	private @Nullable Object ephemera$lookupTracker(Fluid fluid) {
		if (fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER) {
			return this.ephemera$waterTracker;
		}
		if (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) {
			return this.ephemera$lavaTracker;
		}
		for (Map.Entry<TagKey<Fluid>, ?> e : this.trackerByFluid.entrySet()) {
			if (fluid.is(e.getKey())) {
				return e.getValue();
			}
		}
		return null;
	}

	@Unique
	private static @Nullable LevelChunk ephemera$getChunk(Level level, @Nullable FastChunkLookup fast, int cx, int cz) {
		if (fast != null) {
			return fast.ephemera$getLoadedChunk(cx, cz);
		}
		ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
		return chunk instanceof LevelChunk lc ? lc : null;
	}
}
