package com.ephemera.mixin.client.particle;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.common.FastCubeCollision;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zero-allocation full-cube + air/fluid collision fast path for {@link Particle#move}.
 *
 * <p>Resolves particle motion through air, fluids, and adjacent full-cube terrain blocks via
 * {@link FastCubeCollision} without allocating {@code CollisionContext}, {@code Vec3}, expanded
 * {@code AABB}, {@code BlockCollisions}, {@code MutableBlockPos}, or {@code ImmutableList},
 * while matching vanilla's {@code stoppedByCollision}, {@code onGround}, and velocity damping
 * semantics bit-for-bit.
 */
@Mixin(Particle.class)
abstract class ParticleMoveFastMixin {
	@Shadow @Final private static double MAXIMUM_COLLISION_VELOCITY_SQUARED;
	@Shadow @Final protected ClientLevel level;
	@Shadow protected boolean hasPhysics;
	@Shadow private boolean stoppedByCollision;
	@Shadow protected boolean onGround;
	@Shadow protected double xd;
	@Shadow protected double zd;
	@Shadow public abstract AABB getBoundingBox();
	@Shadow public abstract void setBoundingBox(AABB bb);
	@Shadow protected abstract void setLocationFromBoundingbox();

	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastCubeMove(double dx, double dy, double dz, CallbackInfo ci) {
		if (this.stoppedByCollision || !this.hasPhysics || !EphemeraCommon.config().engineFastCollisions) {
			return;
		}
		if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
			return;
		}
		if (dx * dx + dy * dy + dz * dz >= MAXIMUM_COLLISION_VELOCITY_SQUARED) {
			return;
		}
		ClientLevel lvl = this.level;
		if (lvl == null) {
			return;
		}
		AABB bb = this.getBoundingBox();
		double[] out = FastCubeCollision.scratchOut();
		if (!FastCubeCollision.tryCollide(lvl, null, bb, dx, dy, dz, out)) {
			return;
		}

		double rx = out[0];
		double ry = out[1];
		double rz = out[2];
		if (rx != 0.0 || ry != 0.0 || rz != 0.0) {
			this.setBoundingBox(bb.move(rx, ry, rz));
			this.setLocationFromBoundingbox();
		}
		if (Math.abs(dy) >= 9.999999747378752E-6 && Math.abs(ry) < 9.999999747378752E-6) {
			this.stoppedByCollision = true;
		}
		this.onGround = dy != ry && dy < 0.0;
		if (dx != rx) {
			this.xd = 0.0;
		}
		if (dz != rz) {
			this.zd = 0.0;
		}
		ci.cancel();
	}
}
