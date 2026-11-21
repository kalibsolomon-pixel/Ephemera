package com.ephemera.mixin.client.terrain;

import com.ephemera.mixin.common.PaletteIndexAccessor;
import com.ephemera.render.terrain.SectionCopyFastAccess;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Unwraps the immutable {@code PalettedContainer<BlockState>} snapshot once in
 * {@link SectionCopy}'s constructor so chunk-meshing threads read block states with zero
 * volatile {@code data} loads, zero {@code Strategy.getIndex} virtual dispatch, and zero
 * {@code ImmutableMap.get} overhead on sections without block entities.
 */
@Mixin(SectionCopy.class)
public abstract class SectionCopyFastMixin implements SectionCopyFastAccess {
	@Shadow @Final private Map<BlockPos, BlockEntity> blockEntities;
	@Shadow @Final private @Nullable PalettedContainer<BlockState> section;
	@Shadow @Final private boolean debug;

	@Unique private @Nullable BlockState ephemera$singleState;
	@Unique private @Nullable SimpleBitStorage ephemera$simpleStorage;
	@Unique private @Nullable BitStorage ephemera$otherStorage;
	@Unique private @Nullable LinearPalette<BlockState> ephemera$linearPalette;
	@Unique private @Nullable Palette<BlockState> ephemera$otherPalette;
	@Unique private boolean ephemera$hasBlockEntities;

	@SuppressWarnings("unchecked")
	@Inject(method = "<init>", at = @At("TAIL"))
	private void ephemera$unwrapImmutableSnapshot(LevelChunk chunk, int sectionIndex, CallbackInfo ci) {
		this.ephemera$hasBlockEntities = !this.blockEntities.isEmpty();
		if (this.debug) {
			return;
		}
		PalettedContainer<BlockState> container = this.section;
		if (container == null) {
			this.ephemera$singleState = Blocks.AIR.defaultBlockState();
			return;
		}
		if ((Object) container instanceof PaletteIndexAccessor<?> accessor) {
			PalettedContainer.Data<BlockState> data = ((PaletteIndexAccessor<BlockState>) accessor).ephemera$data();
			Palette<BlockState> pal = data.palette();
			BitStorage stor = data.storage();
			if (pal instanceof SingleValuePalette<BlockState> svp) {
				this.ephemera$singleState = svp.valueFor(0);
				return;
			}
			if (stor instanceof ZeroBitStorage) {
				this.ephemera$singleState = pal.valueFor(0);
				return;
			}
			if (stor instanceof SimpleBitStorage sbs) {
				this.ephemera$simpleStorage = sbs;
			} else {
				this.ephemera$otherStorage = stor;
			}
			if (pal instanceof LinearPalette<BlockState> lp) {
				this.ephemera$linearPalette = lp;
			} else {
				this.ephemera$otherPalette = pal;
			}
		}
	}

	@Override
	public BlockState ephemera$getBlockState(int x, int y, int z) {
		BlockState single = this.ephemera$singleState;
		if (single != null) {
			return single;
		}
		if (this.debug) {
			return ephemera$debugState(x, y, z);
		}
		int idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		SimpleBitStorage sbs = this.ephemera$simpleStorage;
		int id;
		if (sbs != null) {
			id = sbs.get(idx);
		} else {
			BitStorage other = this.ephemera$otherStorage;
			if (other != null) {
				id = other.get(idx);
			} else {
				PalettedContainer<BlockState> c = this.section;
				return c == null ? Blocks.AIR.defaultBlockState() : c.get(x & 15, y & 15, z & 15);
			}
		}
		LinearPalette<BlockState> lp = this.ephemera$linearPalette;
		return lp != null ? lp.valueFor(id) : this.ephemera$otherPalette.valueFor(id);
	}

	/**
	 * @author Ephemera
	 * @reason Direct non-volatile palette &amp; bit-storage lookup on immutable section snapshots.
	 */
	@Overwrite
	public BlockState getBlockState(BlockPos pos) {
		BlockState single = this.ephemera$singleState;
		if (single != null) {
			return single;
		}
		return ephemera$getBlockState(pos.getX(), pos.getY(), pos.getZ());
	}

	/**
	 * @author Ephemera
	 * @reason Instant null return when the section snapshot has no block entities.
	 */
	@Overwrite
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return this.ephemera$hasBlockEntities ? this.blockEntities.get(pos) : null;
	}

	@Override
	public @Nullable BlockEntity ephemera$getBlockEntityFast(BlockPos pos) {
		return this.ephemera$hasBlockEntities ? this.blockEntities.get(pos) : null;
	}

	@Unique
	private static BlockState ephemera$debugState(int x, int y, int z) {
		BlockState state = null;
		if (y == 60) {
			state = Blocks.BARRIER.defaultBlockState();
		}
		if (y == 70) {
			state = DebugLevelSource.getBlockStateFor(x, z);
		}
		return state == null ? Blocks.AIR.defaultBlockState() : state;
	}
}
