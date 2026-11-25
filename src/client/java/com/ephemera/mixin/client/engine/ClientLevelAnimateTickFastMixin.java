package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.client.ClientChunkCacheFastAccess;
import com.ephemera.engine.client.ClientChunkStorageFastLookup;
import com.ephemera.mixin.common.LegacyRandomSourceAccessor;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Accelerates {@link ClientLevel#animateTick} (1,334 random block samples per tick):
 * <ul>
 *   <li>Eliminates 8,004 {@code AtomicLong.compareAndSet} hardware fences per tick by advancing
 *       {@link LegacyRandomSource}'s exact 48-bit LCG in a local register and writing it back once;</li>
 *   <li>Caches the 5x5 chunk neighborhood around the player once per tick instead of performing
 *       2,668 chunk-map lookups;</li>
 *   <li>Tracks {@link EnvironmentAttributes#AMBIENT_PARTICLES} per chunk section in a 25-entry
 *       bitmask so distant cave/nether biomes never force 1,000+ {@code Vec3} + Voronoi biome
 *       lookups in Overworld air sections;</li>
 *   <li>Skips air-only sections ({@code hasOnlyAir()}), air states, and empty {@code Block.animateTick}
 *       virtual dispatches in O(1).</li>
 * </ul>
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelAnimateTickFastMixin {
	@Shadow @Final private ClientChunkCache chunkSource;
	@Shadow protected abstract @Nullable Block getMarkerParticleTarget();
	@Shadow protected abstract void trySpawnDripParticles(BlockPos pos, BlockState state, ParticleOptions particle, boolean faceSturdy);

	@Unique private static final long EPHEMERA$LCG_MULT = 25214903917L;
	@Unique private static final long EPHEMERA$LCG_ADD = 11L;
	@Unique private static final long EPHEMERA$LCG_MASK = (1L << 48) - 1L;

	@Unique
	private static final ClassValue<Boolean> EPHEMERA$HAS_ANIMATE_TICK = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			try {
				return type.getMethod("animateTick", BlockState.class, Level.class, BlockPos.class, RandomSource.class)
					.getDeclaringClass() != Block.class;
			} catch (NoSuchMethodException e) {
				return true;
			}
		}
	};

	@Unique
	private static final Predicate<Holder<Biome>> EPHEMERA$BIOME_HAS_AMBIENT =
		holder -> holder != null && holder.value().getAttributes().keySet().contains(EnvironmentAttributes.AMBIENT_PARTICLES);

	@Unique private final LevelChunk[] ephemera$chunkGrid = new LevelChunk[25];
	@Unique private final long[] ephemera$ambientSecMask = new long[25];
	@Unique private final BlockPos.MutableBlockPos ephemera$pos = new BlockPos.MutableBlockPos();
	@Unique private final BlockPos.MutableBlockPos ephemera$belowPos = new BlockPos.MutableBlockPos();

	@Inject(method = "animateTick", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastAnimateTick(int centerX, int centerY, int centerZ, CallbackInfo ci) {
		if (!EphemeraCommon.config().engineFastChunkLookup) {
			return;
		}
		ClientLevel self = (ClientLevel) (Object) this;
		if (self.isDebug()) {
			return;
		}
		RandomSource tickRandom = self.getRandom();
		RandomSource particleRandom = RandomSource.createThreadLocalInstance();
		Block markerBlock = this.getMarkerParticleTarget();
		int minY = self.getMinY();
		int maxY = self.getMaxY();
		int minSecY = minY >> 4;
		int baseCx = (centerX >> 4) - 2;
		int baseCz = (centerZ >> 4) - 2;

		LevelChunk[] grid = this.ephemera$chunkGrid;
		long[] ambMask = this.ephemera$ambientSecMask;
		ClientChunkCache source = this.chunkSource;
		ClientChunkStorageFastLookup fastLookup = source instanceof ClientChunkCacheFastAccess access
			? access.ephemera$storageLookup()
			: null;

		EnvironmentAttributeSystem env = self.environmentAttributes();
		boolean dimHasAmbient = !env.getDimensionValue(EnvironmentAttributes.AMBIENT_PARTICLES).isEmpty();
		int minAmbSec = Math.max(0, ((centerY - 36) >> 4) - minSecY);
		int maxAmbSec = ((centerY + 36) >> 4) - minSecY;
		boolean anyAmbient = dimHasAmbient;

		for (int dz = 0; dz < 5; dz++) {
			int cz = baseCz + dz;
			int row = dz * 5;
			for (int dx = 0; dx < 5; dx++) {
				int cx = baseCx + dx;
				LevelChunk chunk = fastLookup != null
					? fastLookup.ephemera$getChunkIfValid(cx, cz)
					: source.getChunk(cx, cz, ChunkStatus.FULL, false);
				int cell = row + dx;
				grid[cell] = chunk;
				long mask = 0L;
				if (dimHasAmbient) {
					mask = -1L;
				} else if (chunk != null) {
					LevelChunkSection[] sections = chunk.getSections();
					int hi = Math.min(maxAmbSec, Math.min(63, sections.length - 1));
					for (int s = minAmbSec; s <= hi; s++) {
						LevelChunkSection sec = sections[s];
						if (sec != null && sec.getBiomes().maybeHas(EPHEMERA$BIOME_HAS_AMBIENT)) {
							mask |= 1L << s;
							anyAmbient = true;
						}
					}
				}
				ambMask[cell] = mask;
			}
		}

		BlockPos.MutableBlockPos mpos = this.ephemera$pos;
		BlockPos.MutableBlockPos belowPos = this.ephemera$belowPos;

		// Fast path: LegacyRandomSource (vanilla default on ClientLevel) uses AtomicLong.compareAndSet
		// on every nextInt(). Advance the exact 48-bit LCG in a local register and write back once.
		AtomicLong seedRef = (tickRandom instanceof LegacyRandomSource && tickRandom instanceof LegacyRandomSourceAccessor acc)
			? acc.ephemera$seed()
			: null;

		if (seedRef != null) {
			long seed = seedRef.get();
			for (int i = 0; i < 667; i++) {
				// Radius 16 (nextInt(16) on BitRandomSource = next(4) = (int)(seed >>> 44))
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int rx1 = (int) (seed >>> 44);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int bx1 = centerX + rx1 - (int) (seed >>> 44);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int ry1 = (int) (seed >>> 44);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int by1 = centerY + ry1 - (int) (seed >>> 44);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int rz1 = (int) (seed >>> 44);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int bz1 = centerZ + rz1 - (int) (seed >>> 44);

				seed = ephemera$sampleWithSeed(self, grid, ambMask, anyAmbient, dimHasAmbient,
					baseCx, baseCz, minY, maxY, minSecY, bx1, by1, bz1,
					seed, seedRef, tickRandom, particleRandom, markerBlock, env, mpos, belowPos);

				// Radius 32 (nextInt(32) on BitRandomSource = next(5) = (int)(seed >>> 43))
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int rx2 = (int) (seed >>> 43);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int bx2 = centerX + rx2 - (int) (seed >>> 43);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int ry2 = (int) (seed >>> 43);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int by2 = centerY + ry2 - (int) (seed >>> 43);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int rz2 = (int) (seed >>> 43);
				seed = (seed * EPHEMERA$LCG_MULT + EPHEMERA$LCG_ADD) & EPHEMERA$LCG_MASK;
				int bz2 = centerZ + rz2 - (int) (seed >>> 43);

				seed = ephemera$sampleWithSeed(self, grid, ambMask, anyAmbient, dimHasAmbient,
					baseCx, baseCz, minY, maxY, minSecY, bx2, by2, bz2,
					seed, seedRef, tickRandom, particleRandom, markerBlock, env, mpos, belowPos);
			}
			seedRef.set(seed);
		} else {
			for (int i = 0; i < 667; i++) {
				int bx1 = centerX + tickRandom.nextInt(16) - tickRandom.nextInt(16);
				int by1 = centerY + tickRandom.nextInt(16) - tickRandom.nextInt(16);
				int bz1 = centerZ + tickRandom.nextInt(16) - tickRandom.nextInt(16);
				ephemera$sampleGeneric(self, grid, ambMask, anyAmbient, dimHasAmbient,
					baseCx, baseCz, minY, maxY, minSecY, bx1, by1, bz1,
					tickRandom, particleRandom, markerBlock, env, mpos, belowPos);

				int bx2 = centerX + tickRandom.nextInt(32) - tickRandom.nextInt(32);
				int by2 = centerY + tickRandom.nextInt(32) - tickRandom.nextInt(32);
				int bz2 = centerZ + tickRandom.nextInt(32) - tickRandom.nextInt(32);
				ephemera$sampleGeneric(self, grid, ambMask, anyAmbient, dimHasAmbient,
					baseCx, baseCz, minY, maxY, minSecY, bx2, by2, bz2,
					tickRandom, particleRandom, markerBlock, env, mpos, belowPos);
			}
		}

		for (int i = 0; i < 25; i++) {
			grid[i] = null;
		}
		ci.cancel();
	}

	@Unique
	private long ephemera$sampleWithSeed(
		ClientLevel self,
		LevelChunk[] grid,
		long[] ambMask,
		boolean anyAmbient,
		boolean dimHasAmbient,
		int baseCx,
		int baseCz,
		int minY,
		int maxY,
		int minSecY,
		int bx,
		int by,
		int bz,
		long seed,
		AtomicLong seedRef,
		RandomSource tickRandom,
		RandomSource particleRandom,
		@Nullable Block markerBlock,
		EnvironmentAttributeSystem env,
		BlockPos.MutableBlockPos mpos,
		BlockPos.MutableBlockPos belowPos
	) {
		if (by < minY || by >= maxY) {
			if (dimHasAmbient) {
				seedRef.set(seed);
				mpos.set(bx, by, bz);
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
				seed = seedRef.get();
			}
			return seed;
		}
		int gx = (bx >> 4) - baseCx;
		int gz = (bz >> 4) - baseCz;
		int cell = (gx >= 0 && gx < 5 && gz >= 0 && gz < 5) ? (gz * 5 + gx) : -1;
		LevelChunk chunk = cell >= 0 ? grid[cell] : null;
		int secIdx = (by >> 4) - minSecY;
		boolean secAmb = anyAmbient && (dimHasAmbient || (cell >= 0 && secIdx >= 0 && secIdx < 64 && (ambMask[cell] & (1L << secIdx)) != 0L));

		if (chunk == null) {
			if (secAmb) {
				seedRef.set(seed);
				mpos.set(bx, by, bz);
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
				seed = seedRef.get();
			}
			return seed;
		}
		LevelChunkSection[] sections = chunk.getSections();
		LevelChunkSection section = (secIdx >= 0 && secIdx < sections.length) ? sections[secIdx] : null;
		if (section == null || section.hasOnlyAir()) {
			if (secAmb) {
				seedRef.set(seed);
				mpos.set(bx, by, bz);
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
				seed = seedRef.get();
			}
			return seed;
		}

		BlockState state = section.getBlockState(bx & 15, by & 15, bz & 15);
		if (state.isAir()) {
			if (secAmb) {
				seedRef.set(seed);
				mpos.set(bx, by, bz);
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
				seed = seedRef.get();
			}
			return seed;
		}

		Block block = state.getBlock();
		boolean posSet = false;
		if (EPHEMERA$HAS_ANIMATE_TICK.get(block.getClass())) {
			mpos.set(bx, by, bz);
			posSet = true;
			block.animateTick(state, self, mpos, particleRandom);
		}

		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty()) {
			if (!posSet) {
				mpos.set(bx, by, bz);
				posSet = true;
			}
			fluid.animateTick(self, mpos, particleRandom);
			ParticleOptions drip = fluid.getDripParticle();
			if (drip != null) {
				seedRef.set(seed);
				boolean spawn = tickRandom.nextInt(10) == 0;
				seed = seedRef.get();
				if (spawn) {
					boolean sturdy = state.isFaceSturdy(self, mpos, Direction.DOWN);
					belowPos.set(bx, by - 1, bz);
					BlockState belowState = ephemera$getBelowState(chunk, sections, minY, minSecY, bx, by - 1, bz, self, belowPos);
					this.trySpawnDripParticles(belowPos.immutable(), belowState, drip, sturdy);
				}
			}
		}

		if (markerBlock == block) {
			self.addParticle(new BlockParticleOption(ParticleTypes.BLOCK_MARKER, state), bx + 0.5, by + 0.5, bz + 0.5, 0.0, 0.0, 0.0);
		}

		if (secAmb) {
			if (!posSet) {
				mpos.set(bx, by, bz);
			}
			if (!state.isCollisionShapeFullBlock(self, mpos)) {
				seedRef.set(seed);
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
				seed = seedRef.get();
			}
		}
		return seed;
	}

	@Unique
	private void ephemera$sampleGeneric(
		ClientLevel self,
		LevelChunk[] grid,
		long[] ambMask,
		boolean anyAmbient,
		boolean dimHasAmbient,
		int baseCx,
		int baseCz,
		int minY,
		int maxY,
		int minSecY,
		int bx,
		int by,
		int bz,
		RandomSource tickRandom,
		RandomSource particleRandom,
		@Nullable Block markerBlock,
		EnvironmentAttributeSystem env,
		BlockPos.MutableBlockPos mpos,
		BlockPos.MutableBlockPos belowPos
	) {
		if (by < minY || by >= maxY) {
			if (dimHasAmbient) {
				mpos.set(bx, by, bz);
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
			}
			return;
		}
		int gx = (bx >> 4) - baseCx;
		int gz = (bz >> 4) - baseCz;
		int cell = (gx >= 0 && gx < 5 && gz >= 0 && gz < 5) ? (gz * 5 + gx) : -1;
		LevelChunk chunk = cell >= 0 ? grid[cell] : null;
		int secIdx = (by >> 4) - minSecY;
		boolean secAmb = anyAmbient && (dimHasAmbient || (cell >= 0 && secIdx >= 0 && secIdx < 64 && (ambMask[cell] & (1L << secIdx)) != 0L));

		if (chunk == null) {
			if (secAmb) {
				mpos.set(bx, by, bz);
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
			}
			return;
		}
		LevelChunkSection[] sections = chunk.getSections();
		LevelChunkSection section = (secIdx >= 0 && secIdx < sections.length) ? sections[secIdx] : null;
		if (section == null || section.hasOnlyAir()) {
			if (secAmb) {
				mpos.set(bx, by, bz);
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
			}
			return;
		}

		BlockState state = section.getBlockState(bx & 15, by & 15, bz & 15);
		if (state.isAir()) {
			if (secAmb) {
				mpos.set(bx, by, bz);
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
			}
			return;
		}

		Block block = state.getBlock();
		boolean posSet = false;
		if (EPHEMERA$HAS_ANIMATE_TICK.get(block.getClass())) {
			mpos.set(bx, by, bz);
			posSet = true;
			block.animateTick(state, self, mpos, particleRandom);
		}

		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty()) {
			if (!posSet) {
				mpos.set(bx, by, bz);
				posSet = true;
			}
			fluid.animateTick(self, mpos, particleRandom);
			ParticleOptions drip = fluid.getDripParticle();
			if (drip != null && tickRandom.nextInt(10) == 0) {
				boolean sturdy = state.isFaceSturdy(self, mpos, Direction.DOWN);
				belowPos.set(bx, by - 1, bz);
				BlockState belowState = ephemera$getBelowState(chunk, sections, minY, minSecY, bx, by - 1, bz, self, belowPos);
				this.trySpawnDripParticles(belowPos.immutable(), belowState, drip, sturdy);
			}
		}

		if (markerBlock == block) {
			self.addParticle(new BlockParticleOption(ParticleTypes.BLOCK_MARKER, state), bx + 0.5, by + 0.5, bz + 0.5, 0.0, 0.0, 0.0);
		}

		if (secAmb) {
			if (!posSet) {
				mpos.set(bx, by, bz);
			}
			if (!state.isCollisionShapeFullBlock(self, mpos)) {
				ephemera$spawnAmbient(self, env, tickRandom, mpos);
			}
		}
	}

	@Unique
	private static BlockState ephemera$getBelowState(
		LevelChunk chunk,
		LevelChunkSection[] sections,
		int minY,
		int minSecY,
		int bx,
		int by,
		int bz,
		ClientLevel self,
		BlockPos.MutableBlockPos belowPos
	) {
		if (by < minY) {
			return self.getBlockState(belowPos);
		}
		int secIdx = (by >> 4) - minSecY;
		if (secIdx >= 0 && secIdx < sections.length) {
			LevelChunkSection sec = sections[secIdx];
			if (sec == null || sec.hasOnlyAir()) {
				return Blocks.AIR.defaultBlockState();
			}
			return sec.getBlockState(bx & 15, by & 15, bz & 15);
		}
		return self.getBlockState(belowPos);
	}

	@Unique
	private static void ephemera$spawnAmbient(
		ClientLevel self,
		EnvironmentAttributeSystem env,
		RandomSource tickRandom,
		BlockPos.MutableBlockPos mpos
	) {
		List<AmbientParticle> list = env.getValue(EnvironmentAttributes.AMBIENT_PARTICLES, mpos);
		for (int i = 0, n = list.size(); i < n; i++) {
			AmbientParticle ap = list.get(i);
			if (ap.canSpawn(tickRandom)) {
				self.addParticle(
					ap.particle(),
					mpos.getX() + tickRandom.nextDouble(),
					mpos.getY() + tickRandom.nextDouble(),
					mpos.getZ() + tickRandom.nextDouble(),
					0.0, 0.0, 0.0
				);
			}
		}
	}
}
