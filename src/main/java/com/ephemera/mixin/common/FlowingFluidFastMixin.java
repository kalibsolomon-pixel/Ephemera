package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.common.FastChunkLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates per-call {@code MutableBlockPos}, {@code Direction.Plane.HORIZONTAL.iterator()},
 * {@code BlockPos.below()/above()}, double chunk-map lookups, and unnecessary {@code Vec3}
 * allocations inside {@link FlowingFluid#getFlow}. Compatible with both MC 26.2
 * ({@code BlockState.blocksMotion()}) and MC 26.3 ({@code BlockTags.BLOCKS_FLUID_FLOW}).
 */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidFastMixin {
	@Shadow protected abstract boolean affectsFlow(FluidState state);
	@Shadow protected abstract boolean isSolidFace(BlockGetter level, BlockPos pos, Direction direction);

	@Unique
	private static final Direction[] EPHEMERA$HORIZONTAL = {
		Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	@Unique
	private static final ThreadLocal<BlockPos.MutableBlockPos> EPHEMERA$POS =
		ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

	@Unique
	@SuppressWarnings("unchecked")
	private static final @Nullable TagKey<Block> EPHEMERA$BLOCKS_FLUID_FLOW_TAG = ephemera$resolveFluidFlowTag();

	@Unique
	private static final @Nullable MethodHandle EPHEMERA$BLOCKS_MOTION =
		EPHEMERA$BLOCKS_FLUID_FLOW_TAG == null ? ephemera$resolveBlocksMotion() : null;

	@Unique
	@SuppressWarnings("unchecked")
	private static @Nullable TagKey<Block> ephemera$resolveFluidFlowTag() {
		try {
			Object val = BlockTags.class.getField("BLOCKS_FLUID_FLOW").get(null);
			return val instanceof TagKey<?> tk ? (TagKey<Block>) tk : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	@Unique
	private static @Nullable MethodHandle ephemera$resolveBlocksMotion() {
		try {
			return MethodHandles.publicLookup().findVirtual(
				BlockBehaviour.BlockStateBase.class,
				"blocksMotion",
				MethodType.methodType(boolean.class)
			);
		} catch (Throwable ignored) {
			return null;
		}
	}

	@Unique
	private static boolean ephemera$blocksFluidFlow(BlockState state) {
		if (state.isAir()) {
			return false;
		}
		if (EPHEMERA$BLOCKS_FLUID_FLOW_TAG != null) {
			return state.is(EPHEMERA$BLOCKS_FLUID_FLOW_TAG);
		}
		MethodHandle mh = EPHEMERA$BLOCKS_MOTION;
		if (mh != null) {
			try {
				return (boolean) mh.invokeExact((BlockBehaviour.BlockStateBase) state);
			} catch (Throwable ignored) {
				return true;
			}
		}
		return true;
	}

	@Inject(method = "getFlow", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastGetFlow(
		BlockGetter level,
		BlockPos pos,
		FluidState fluidState,
		CallbackInfoReturnable<Vec3> cir
	) {
		if (!EphemeraCommon.config().engineFastChunkLookup
			|| (EPHEMERA$BLOCKS_FLUID_FLOW_TAG == null && EPHEMERA$BLOCKS_MOTION == null)) {
			return;
		}
		int bx = pos.getX();
		int by = pos.getY();
		int bz = pos.getZ();
		float selfHeight = fluidState.getOwnHeight();
		double fx = 0.0;
		double fz = 0.0;

		Level world = (level instanceof Level lvl && !lvl.isDebug()) ? lvl : null;
		int baseCx = bx >> 4;
		int baseCz = bz >> 4;
		LevelChunk baseChunk = null;
		LevelChunkSection[] baseSections = null;
		int minY = 0;
		int maxY = 0;
		int minSecY = 0;
		if (world != null) {
			minY = world.getMinY();
			maxY = world.getMaxY();
			minSecY = minY >> 4;
			LevelChunk lc = world instanceof FastChunkLookup fast
				? fast.ephemera$getLoadedChunk(baseCx, baseCz)
				: (world.getChunk(baseCx, baseCz, ChunkStatus.FULL, false) instanceof LevelChunk c ? c : null);
			if (lc != null) {
				baseChunk = lc;
				baseSections = lc.getSections();
			}
		}

		BlockPos.MutableBlockPos mpos = EPHEMERA$POS.get();
		for (int i = 0; i < 4; i++) {
			Direction dir = EPHEMERA$HORIZONTAL[i];
			int nx = bx + dir.getStepX();
			int nz = bz + dir.getStepZ();
			BlockState neighborState = ephemera$blockState(level, baseChunk, baseSections, baseCx, baseCz, minY, maxY, minSecY, nx, by, nz, mpos);
			FluidState neighborFluid = neighborState.getFluidState();
			if (!this.affectsFlow(neighborFluid)) {
				continue;
			}
			float nh = neighborFluid.getOwnHeight();
			float diff = 0.0f;
			if (nh == 0.0f) {
				if (!ephemera$blocksFluidFlow(neighborState)) {
					BlockState belowState = ephemera$blockState(level, baseChunk, baseSections, baseCx, baseCz, minY, maxY, minSecY, nx, by - 1, nz, mpos);
					FluidState belowFluid = belowState.getFluidState();
					if (this.affectsFlow(belowFluid)) {
						nh = belowFluid.getOwnHeight();
						if (nh > 0.0f) {
							diff = selfHeight - (nh - 0.8888889f);
						}
					}
				}
			} else if (nh > 0.0f) {
				diff = selfHeight - nh;
			}
			if (diff != 0.0f) {
				fx += dir.getStepX() * diff;
				fz += dir.getStepZ() * diff;
			}
		}

		boolean falling = Boolean.TRUE.equals(fluidState.getValue(FlowingFluid.FALLING));
		double fy = 0.0;
		if (falling) {
			for (int i = 0; i < 4; i++) {
				Direction dir = EPHEMERA$HORIZONTAL[i];
				int nx = bx + dir.getStepX();
				int nz = bz + dir.getStepZ();
				mpos.set(nx, by, nz);
				boolean solid = this.isSolidFace(level, mpos, dir);
				if (!solid) {
					mpos.set(nx, by + 1, nz);
					solid = this.isSolidFace(level, mpos, dir);
				}
				if (solid) {
					double len = Math.sqrt(fx * fx + fz * fz);
					if (len < 1.0E-4) {
						fx = 0.0;
						fz = 0.0;
					} else {
						fx /= len;
						fz /= len;
					}
					fy = -6.0;
					break;
				}
			}
		}

		double lenSq = fx * fx + fy * fy + fz * fz;
		if (lenSq < 1.0E-8) {
			cir.setReturnValue(Vec3.ZERO);
			return;
		}
		double invLen = 1.0 / Math.sqrt(lenSq);
		cir.setReturnValue(new Vec3(fx * invLen, fy * invLen, fz * invLen));
	}

	@Unique
	private static BlockState ephemera$blockState(
		BlockGetter level,
		LevelChunk baseChunk,
		LevelChunkSection[] baseSections,
		int baseCx,
		int baseCz,
		int minY,
		int maxY,
		int minSecY,
		int x,
		int y,
		int z,
		BlockPos.MutableBlockPos mpos
	) {
		if (baseChunk != null && (x >> 4) == baseCx && (z >> 4) == baseCz && y >= minY && y < maxY) {
			int secIdx = (y >> 4) - minSecY;
			if (secIdx >= 0 && secIdx < baseSections.length) {
				LevelChunkSection sec = baseSections[secIdx];
				return (sec == null || sec.hasOnlyAir())
					? Blocks.AIR.defaultBlockState()
					: sec.getBlockState(x & 15, y & 15, z & 15);
			}
		}
		mpos.set(x, y, z);
		return level.getBlockState(mpos);
	}
}
