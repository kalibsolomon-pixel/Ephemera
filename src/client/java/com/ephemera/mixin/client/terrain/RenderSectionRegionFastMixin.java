package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.SectionCopyFastAccess;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Eliminates duplicate {@code BlockPos.getX/Y/Z} reads and delegates directly to
 * {@link SectionCopyFastAccess} with primitive coordinates during chunk meshing &amp; AO lighting.
 */
@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionFastMixin {
	@Shadow @Final private int minSectionX;
	@Shadow @Final private int minSectionY;
	@Shadow @Final private int minSectionZ;
	@Shadow @Final private SectionCopy[] sections;

	/**
	 * @author Ephemera
	 * @reason Single coordinate unpack + direct primitive SectionCopy lookup.
	 */
	@Overwrite
	public BlockState getBlockState(BlockPos pos) {
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();
		int idx = ((x >> 4) - this.minSectionX)
			+ ((y >> 4) - this.minSectionY) * 3
			+ ((z >> 4) - this.minSectionZ) * 9;
		return ((SectionCopyFastAccess) (Object) this.sections[idx]).ephemera$getBlockState(x, y, z);
	}

	/**
	 * @author Ephemera
	 * @reason Single coordinate unpack + direct primitive SectionCopy lookup for fluid states.
	 */
	@Overwrite
	public FluidState getFluidState(BlockPos pos) {
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();
		int idx = ((x >> 4) - this.minSectionX)
			+ ((y >> 4) - this.minSectionY) * 3
			+ ((z >> 4) - this.minSectionZ) * 9;
		return ((SectionCopyFastAccess) (Object) this.sections[idx]).ephemera$getBlockState(x, y, z).getFluidState();
	}

	/**
	 * @author Ephemera
	 * @reason Single coordinate unpack + fast empty-map bypass on SectionCopy.
	 */
	@Overwrite
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		int idx = ((pos.getX() >> 4) - this.minSectionX)
			+ ((pos.getY() >> 4) - this.minSectionY) * 3
			+ ((pos.getZ() >> 4) - this.minSectionZ) * 9;
		return ((SectionCopyFastAccess) (Object) this.sections[idx]).ephemera$getBlockEntityFast(pos);
	}
}
