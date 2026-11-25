package com.ephemera.mixin.client.engine;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Zero-allocation fast path for {@link Camera#getFluidInCamera()}: replaces the per-frame
 * {@code NearPlane}, 13+ {@link Vec3}, {@code Vec3[]}, {@code Arrays.asList}, {@code Iterator},
 * and 5x {@link BlockPos#containing} allocations with primitive near-plane corner math, one
 * reusable {@link BlockPos.MutableBlockPos}, and same-block air deduplication.
 */
@Mixin(Camera.class)
public abstract class CameraFluidFastMixin {
	@Shadow private boolean initialized;
	@Shadow private Level level;
	@Shadow private Vec3 position;
	@Shadow @Final private BlockPos.MutableBlockPos blockPosition;
	@Shadow @Final private Vector3f forwards;
	@Shadow @Final private Vector3f up;
	@Shadow @Final private Vector3f left;
	@Shadow @Final private Projection projection;
	@Shadow @Final private Minecraft minecraft;

	@Unique private final BlockPos.MutableBlockPos ephemera$probePos = new BlockPos.MutableBlockPos();

	@WrapMethod(method = "getFluidInCamera")
	private FogType ephemera$zeroAllocFluidInCamera(Operation<FogType> original) {
		if (!EphemeraCommon.config().engineIdleShortCircuits) {
			return original.call();
		}
		if (!this.initialized) {
			return FogType.NONE;
		}
		Level lvl = this.level;
		Vec3 pos = this.position;
		BlockPos.MutableBlockPos camBlock = this.blockPosition;
		FluidState centerFluid = lvl.getFluidState(camBlock);
		if (centerFluid.is(FluidTags.WATER)
			&& pos.y < (double) ((float) camBlock.getY() + centerFluid.getHeight(lvl, camBlock))) {
			return FogType.WATER;
		}

		double aspect = (double) this.projection.width() / (double) this.projection.height();
		double zNear = (double) this.projection.zNear();
		float fov = (float) this.minecraft.options.fov().get();
		double halfHeight = Math.tan((double) (fov * 0.017453292F) / 2.0) * zNear;
		double halfWidth = halfHeight * aspect;

		double fx = (double) this.forwards.x * zNear;
		double fy = (double) this.forwards.y * zNear;
		double fz = (double) this.forwards.z * zNear;
		double lx = (double) this.left.x * halfWidth;
		double ly = (double) this.left.y * halfWidth;
		double lz = (double) this.left.z * halfWidth;
		double ux = (double) this.up.x * halfHeight;
		double uy = (double) this.up.y * halfHeight;
		double uz = (double) this.up.z * halfHeight;

		double px = pos.x;
		double py = pos.y;
		double pz = pos.z;

		boolean hasSafe = false;
		int safeX = 0, safeY = 0, safeZ = 0;
		BlockPos.MutableBlockPos probe = this.ephemera$probePos;

		for (int i = 0; i < 5; i++) {
			double ox, oy, oz;
			switch (i) {
				case 0 -> { ox = fx; oy = fy; oz = fz; }
				case 1 -> { ox = fx + ux + lx; oy = fy + uy + ly; oz = fz + uz + lz; }
				case 2 -> { ox = fx + ux - lx; oy = fy + uy - ly; oz = fz + uz - lz; }
				case 3 -> { ox = fx - ux + lx; oy = fy - uy + ly; oz = fz - uz + lz; }
				default -> { ox = fx - ux - lx; oy = fy - uy - ly; oz = fz - uz - lz; }
			}
			double wx = px + ox;
			double wy = py + oy;
			double wz = pz + oz;
			int bx = Mth.floor(wx);
			int by = Mth.floor(wy);
			int bz = Mth.floor(wz);
			if (hasSafe && bx == safeX && by == safeY && bz == safeZ) {
				continue;
			}
			probe.set(bx, by, bz);
			FluidState fluid = lvl.getFluidState(probe);
			if (fluid.is(FluidTags.LAVA)) {
				if (wy <= (double) (fluid.getHeight(lvl, probe) + (float) by)) {
					return FogType.LAVA;
				}
			} else if (lvl.getBlockState(probe).is(Blocks.POWDER_SNOW)) {
				return FogType.POWDER_SNOW;
			} else {
				hasSafe = true;
				safeX = bx;
				safeY = by;
				safeZ = bz;
			}
		}
		return FogType.NONE;
	}
}
