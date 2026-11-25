package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.engine.common.FastChunkLookup;
import com.ephemera.engine.common.FastCubeCollision;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zero-allocation fast paths for the hottest per-entity tick operations:
 * <ul>
 *   <li>Both {@link Entity#collideBoundingBox} overloads: resolves air, fluid, and full-cube
 *       block collisions via {@link FastCubeCollision} without allocating {@code BlockCollisions},
 *       {@code CollisionContext}, {@code VoxelShape}, or {@code ImmutableList};</li>
 *   <li>{@link Entity#collide}: skips {@code Level.getEntityCollisions} (and its
 *       {@code EntitySectionStorage} traversal + {@code ArrayList} allocation) when the level
 *       contains no collidable entities (boats/shulkers/minecarts);</li>
 *   <li>{@link Entity#checkInsideBlocks}: short-circuits stationary and sub-block moves whose
 *       swept cells contain only air or blocks that inherit the empty
 *       {@link BlockBehaviour#entityInside} method with empty fluid state.</li>
 * </ul>
 */
@Mixin(Entity.class)
abstract class EntityFastTickMixin {
	@Shadow private Level level;
	@Shadow public abstract AABB getBoundingBox();
	@Shadow public abstract Vec3 position();
	@Shadow protected abstract AABB makeBoundingBox(Vec3 pos);

	@Unique
	private static final double EPHEMERA$SINGLE_STEP_DIST_SQR = 0.9999900000002526 * 0.9999900000002526;

	@Unique
	private static final ClassValue<Boolean> EPHEMERA$HAS_ENTITY_INSIDE = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			for (Class<?> c = type; c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
				for (var m : c.getDeclaredMethods()) {
					if ("entityInside".equals(m.getName())) {
						return true;
					}
				}
			}
			return false;
		}
	};

	@Unique
	private static final ClassValue<Boolean> EPHEMERA$CAN_COLLIDE_OVERRIDE = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			for (Class<?> c = type; c != null && c != Entity.class; c = c.getSuperclass()) {
				for (var m : c.getDeclaredMethods()) {
					String n = m.getName();
					if ("canCollideWith".equals(n) || "canBeCollidedWith".equals(n)) {
						return true;
					}
				}
			}
			return false;
		}
	};

	@WrapOperation(
		method = "collide",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
		)
	)
	private List<VoxelShape> ephemera$skipEmptyEntityCollisions(
		Level lvl,
		@Nullable Entity entity,
		AABB box,
		Operation<List<VoxelShape>> original
	) {
		if (EphemeraCommon.config().engineFastCollisions
			&& lvl instanceof FastChunkLookup lookup
			&& !lookup.ephemera$hasCollidableEntities()
			&& (entity == null || !EPHEMERA$CAN_COLLIDE_OVERRIDE.get(entity.getClass()))) {
			return List.of();
		}
		return original.call(lvl, entity, box);
	}

	@Inject(
		method = "collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void ephemera$fastCollideBoundingBoxEntity(
		@Nullable Entity entity,
		Vec3 movement,
		AABB box,
		Level level,
		List<VoxelShape> entityCollisions,
		CallbackInfoReturnable<Vec3> cir
	) {
		if (!EphemeraCommon.config().engineFastCollisions || !entityCollisions.isEmpty()) {
			return;
		}
		double[] out = FastCubeCollision.scratchOut();
		if (FastCubeCollision.tryCollide(level, entity, box, movement.x, movement.y, movement.z, out)) {
			double rx = out[0];
			double ry = out[1];
			double rz = out[2];
			if (rx == movement.x && ry == movement.y && rz == movement.z) {
				cir.setReturnValue(movement);
			} else if (rx == 0.0 && ry == 0.0 && rz == 0.0) {
				cir.setReturnValue(Vec3.ZERO);
			} else {
				cir.setReturnValue(new Vec3(rx, ry, rz));
			}
		}
	}

	@Inject(
		method = "collideBoundingBox(Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void ephemera$fastCollideBoundingBoxContext(
		CollisionContext context,
		Vec3 movement,
		AABB box,
		Level level,
		List<VoxelShape> entityCollisions,
		CallbackInfoReturnable<Vec3> cir
	) {
		if (!EphemeraCommon.config().engineFastCollisions || !entityCollisions.isEmpty()) {
			return;
		}
		double[] out = FastCubeCollision.scratchOut();
		if (FastCubeCollision.tryCollide(level, null, box, movement.x, movement.y, movement.z, out)) {
			double rx = out[0];
			double ry = out[1];
			double rz = out[2];
			if (rx == movement.x && ry == movement.y && rz == movement.z) {
				cir.setReturnValue(movement);
			} else if (rx == 0.0 && ry == 0.0 && rz == 0.0) {
				cir.setReturnValue(Vec3.ZERO);
			} else {
				cir.setReturnValue(new Vec3(rx, ry, rz));
			}
		}
	}

	@Inject(
		method = "checkInsideBlocks(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/InsideBlockEffectApplier$StepBasedCollector;Lit/unimi/dsi/fastutil/longs/LongSet;I)I",
		at = @At("HEAD"),
		cancellable = true
	)
	private void ephemera$fastCheckInsideBlocks(
		Vec3 from,
		Vec3 to,
		InsideBlockEffectApplier.StepBasedCollector collector,
		LongSet visited,
		int remainingSteps,
		CallbackInfoReturnable<Integer> cir
	) {
		if (!EphemeraCommon.config().engineFastCollisions || remainingSteps <= 0) {
			return;
		}
		Level lvl = this.level;
		if (lvl == null || lvl.isDebug()) {
			return;
		}
		if (lvl instanceof ServerLevel sl
			&& sl.getServer().debugSubscribers().hasAnySubscriberFor(DebugSubscriptions.ENTITY_BLOCK_INTERSECTIONS)) {
			return;
		}
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		if (dx * dx + dy * dy + dz * dz > EPHEMERA$SINGLE_STEP_DIST_SQR) {
			return;
		}
		Vec3 curPos = this.position();
		AABB bbTo = (to == curPos || (to.x == curPos.x && to.y == curPos.y && to.z == curPos.z))
			? this.getBoundingBox()
			: this.makeBoundingBox(to);
		double eps = 9.999999747378752E-6;
		double minX = (dx < 0.0 ? bbTo.minX : bbTo.minX - dx) + eps;
		double maxX = (dx > 0.0 ? bbTo.maxX : bbTo.maxX - dx) - eps;
		double minY = (dy < 0.0 ? bbTo.minY : bbTo.minY - dy) + eps;
		double maxY = (dy > 0.0 ? bbTo.maxY : bbTo.maxY - dy) - eps;
		double minZ = (dz < 0.0 ? bbTo.minZ : bbTo.minZ - dz) + eps;
		double maxZ = (dz > 0.0 ? bbTo.maxZ : bbTo.maxZ - dz) - eps;

		int x0 = Mth.floor(minX);
		int x1 = Mth.floor(maxX);
		int y0 = Mth.floor(minY);
		int y1 = Mth.floor(maxY);
		int z0 = Mth.floor(minZ);
		int z1 = Mth.floor(maxZ);
		if (x0 > x1 || y0 > y1 || z0 > z1) {
			cir.setReturnValue(1);
			return;
		}
		if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 16L) {
			return;
		}
		int minLevelY = lvl.getMinY();
		int maxLevelY = lvl.getMaxY();
		if (y0 < minLevelY || y1 >= maxLevelY) {
			return;
		}
		int minSecY = minLevelY >> 4;
		int cx0 = x0 >> 4;
		int cx1 = x1 >> 4;
		int cz0 = z0 >> 4;
		int cz1 = z1 >> 4;
		int sy0 = (y0 >> 4) - minSecY;
		int sy1 = (y1 >> 4) - minSecY;
		FastChunkLookup fast = lvl instanceof FastChunkLookup f ? f : null;

		for (int cz = cz0; cz <= cz1; cz++) {
			int lz0 = (cz == cz0) ? (z0 & 15) : 0;
			int lz1 = (cz == cz1) ? (z1 & 15) : 15;
			for (int cx = cx0; cx <= cx1; cx++) {
				LevelChunk lc;
				if (fast != null) {
					lc = fast.ephemera$getLoadedChunk(cx, cz);
				} else {
					ChunkAccess chunk = lvl.getChunk(cx, cz, ChunkStatus.FULL, false);
					lc = chunk instanceof LevelChunk full ? full : null;
				}
				if (lc == null) {
					return;
				}
				LevelChunkSection[] sections = lc.getSections();
				int lx0 = (cx == cx0) ? (x0 & 15) : 0;
				int lx1 = (cx == cx1) ? (x1 & 15) : 15;
				for (int sy = sy0; sy <= sy1; sy++) {
					if (sy < 0 || sy >= sections.length) {
						return;
					}
					LevelChunkSection sec = sections[sy];
					if (sec == null || sec.hasOnlyAir()) {
						continue;
					}
					int ly0 = (sy == sy0) ? (y0 & 15) : 0;
					int ly1 = (sy == sy1) ? (y1 & 15) : 15;
					for (int y = ly0; y <= ly1; y++) {
						for (int z = lz0; z <= lz1; z++) {
							for (int x = lx0; x <= lx1; x++) {
								BlockState state = sec.getBlockState(x, y, z);
								if (state.isAir()) {
									continue;
								}
								if (!state.getFluidState().isEmpty()
									|| EPHEMERA$HAS_ENTITY_INSIDE.get(state.getBlock().getClass())) {
									return;
								}
							}
						}
					}
				}
			}
		}
		cir.setReturnValue(1);
	}
}
