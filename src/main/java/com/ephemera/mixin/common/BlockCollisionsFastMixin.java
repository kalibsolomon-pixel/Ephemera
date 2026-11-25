package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Accelerates {@link BlockCollisions#computeNext} without replacing the loop:
 * <ul>
 *   <li>Defers {@code Shapes.create(box)} ({@code CubeVoxelShape} + 3 {@code DoubleArrayList}s)
 *       until a non-full-cube block actually overlaps, eliminating ~89 MB of render/server allocs.</li>
 *   <li>Caches the active {@link LevelChunkSection} on standard non-debug {@link LevelChunk}s so
 *       air-only sections and repeated block lookups bypass full chunk section indexing.</li>
 *   <li>Skips allocating moved {@link VoxelShape}s when the shape is empty or its translated AABB
 *       is disjoint from the entity collision box under vanilla's {@code 1.0E-7} tolerance.</li>
 * </ul>
 */
@Mixin(BlockCollisions.class)
abstract class BlockCollisionsFastMixin {
	@Shadow @Final private AABB box;
	@Shadow @Final private BlockPos.MutableBlockPos pos;

	@Unique private VoxelShape ephemera$lazyEntityShape;
	@Unique private LevelChunk ephemera$lastChunk;
	@Unique private LevelChunkSection[] ephemera$lastSections;
	@Unique private int ephemera$lastMinSection;
	@Unique private int ephemera$lastSectionIdx = Integer.MIN_VALUE;
	@Unique private LevelChunkSection ephemera$lastSection;

	@WrapOperation(
		method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
		)
	)
	private VoxelShape ephemera$deferEntityShape(AABB aabb, Operation<VoxelShape> original) {
		return EphemeraCommon.config().engineFastCollisions ? null : original.call(aabb);
	}

	@WrapOperation(
		method = "computeNext",
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/world/level/BlockCollisions;entityShape:Lnet/minecraft/world/phys/shapes/VoxelShape;",
			opcode = Opcodes.GETFIELD
		)
	)
	private VoxelShape ephemera$getOrCreateEntityShape(BlockCollisions instance, Operation<VoxelShape> original) {
		VoxelShape existing = original.call(instance);
		if (existing != null) {
			return existing;
		}
		VoxelShape lazy = this.ephemera$lazyEntityShape;
		if (lazy == null) {
			lazy = Shapes.create(this.box);
			this.ephemera$lazyEntityShape = lazy;
		}
		return lazy;
	}

	@WrapOperation(
		method = "computeNext",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
		)
	)
	private BlockState ephemera$fastGetBlockState(
		BlockGetter getter,
		BlockPos blockPos,
		Operation<BlockState> original
	) {
		if (!EphemeraCommon.config().engineFastCollisions || getter.getClass() != LevelChunk.class) {
			return original.call(getter, blockPos);
		}
		LevelChunk chunk = (LevelChunk) getter;
		if (chunk != this.ephemera$lastChunk) {
			if (chunk.getLevel().isDebug()) {
				return original.call(getter, blockPos);
			}
			this.ephemera$lastChunk = chunk;
			this.ephemera$lastSections = chunk.getSections();
			this.ephemera$lastMinSection = chunk.getMinSectionY();
			this.ephemera$lastSectionIdx = Integer.MIN_VALUE;
			this.ephemera$lastSection = null;
		}
		int y = blockPos.getY();
		int secIdx = (y >> 4) - this.ephemera$lastMinSection;
		LevelChunkSection[] sections = this.ephemera$lastSections;
		if (secIdx < 0 || secIdx >= sections.length) {
			return original.call(getter, blockPos);
		}
		LevelChunkSection section;
		if (secIdx == this.ephemera$lastSectionIdx) {
			section = this.ephemera$lastSection;
		} else {
			section = sections[secIdx];
			this.ephemera$lastSectionIdx = secIdx;
			this.ephemera$lastSection = section;
		}
		if (section == null || section.hasOnlyAir()) {
			return Blocks.AIR.defaultBlockState();
		}
		return section.getBlockState(blockPos.getX() & 15, y & 15, blockPos.getZ() & 15);
	}

	@WrapOperation(
		method = "computeNext",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/phys/shapes/VoxelShape;move(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
			ordinal = 1
		)
	)
	private VoxelShape ephemera$skipDisjointMove(
		VoxelShape shape,
		Vec3i offset,
		Operation<VoxelShape> original
	) {
		if (!EphemeraCommon.config().engineFastCollisions) {
			return original.call(shape, offset);
		}
		if (shape.isEmpty()) {
			return Shapes.empty();
		}
		int bx = offset.getX();
		int by = offset.getY();
		int bz = offset.getZ();
		AABB b = this.box;
		if (shape.max(Direction.Axis.X) + bx < b.minX - 1.0E-7
			|| b.maxX < shape.min(Direction.Axis.X) + bx - 1.0E-7
			|| shape.max(Direction.Axis.Y) + by < b.minY - 1.0E-7
			|| b.maxY < shape.min(Direction.Axis.Y) + by - 1.0E-7
			|| shape.max(Direction.Axis.Z) + bz < b.minZ - 1.0E-7
			|| b.maxZ < shape.min(Direction.Axis.Z) + bz - 1.0E-7) {
			return Shapes.empty();
		}
		return original.call(shape, offset);
	}
}
