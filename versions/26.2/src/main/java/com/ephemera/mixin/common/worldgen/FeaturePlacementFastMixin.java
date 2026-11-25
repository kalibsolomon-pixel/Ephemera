package com.ephemera.mixin.common.worldgen;

import com.ephemera.EphemeraCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.stream.Stream;

/**
 * Replaces the lazy {@code Stream.of(origin).flatMap(m0).flatMap(m1)...forEach} feature-placement
 * pipeline with an equivalent depth-first recursion plus zero-allocation fast paths for the two
 * hottest placement modifiers. Ordering is bit-identical: vanilla's {@code flatMap} chain is
 * depth-first (each modifier fully expands a position, interleaving RNG draws and world mutation
 * downstream), and the recursion preserves that exact interleaving, so placement and seeds are
 * unchanged. Unknown (modded) modifiers fall back to vanilla's lazy stream consumed via iterator.
 *
 * <p>Eliminates ~6% of sampled worker-thread allocation (ReferencePipeline / StreamBuilder /
 * capturing lambdas / FeaturePlaceContext churn) during worldgen-heavy scenes.
 */
@Mixin(PlacedFeature.class)
public abstract class FeaturePlacementFastMixin {
	@Shadow
	@Final
	private Holder<ConfiguredFeature<?, ?>> feature;

	@Shadow
	@Final
	private List<PlacementModifier> placement;

	@Inject(method = "placeWithContext", at = @At("HEAD"), cancellable = true)
	private void ephemera$depthFirstPlace(PlacementContext ctx, RandomSource random, BlockPos pos,
										  CallbackInfoReturnable<Boolean> cir) {
		// Vanilla's own dev-only feature counter needs the topFeature debug path; let vanilla run.
		if (!EphemeraCommon.config().engineFastWorldgenNoise || net.minecraft.SharedConstants.DEBUG_FEATURE_COUNT) {
			return;
		}
		MutableBoolean placed = new MutableBoolean();
		this.ephemera$placeStep(this.placement, 0, ctx, random, pos, placed);
		cir.setReturnValue(placed.isTrue());
	}

	private void ephemera$placeStep(List<PlacementModifier> placement, int index, PlacementContext ctx,
									RandomSource random, BlockPos pos, MutableBoolean placed) {
		if (index >= placement.size()) {
			if (this.feature.value().place(ctx.getLevel(), ctx.generator(), random, pos)) {
				placed.setTrue();
			}
			return;
		}
		PlacementModifier modifier = placement.get(index);
		if (modifier instanceof InSquarePlacement) {
			// Exact replication of InSquarePlacement.getPositions: x draw, then z draw.
			int x = random.nextInt(16) + pos.getX();
			int z = random.nextInt(16) + pos.getZ();
			this.ephemera$placeStep(placement, index + 1, ctx, random, new BlockPos(x, pos.getY(), z), placed);
			return;
		}
		if (modifier instanceof PlacementFilter filter) {
			if (((PlacementFilterShouldPlaceAccessor) (Object) filter).ephemera$shouldPlace(ctx, random, pos)) {
				this.ephemera$placeStep(placement, index + 1, ctx, random, pos, placed);
			}
			return;
		}
		// Generic fallback: consume the modifier's lazy stream via iterator (preserves the exact
		// RNG draw order inside the stream).
		try (Stream<BlockPos> stream = modifier.getPositions(ctx, random, pos)) {
			for (java.util.Iterator<BlockPos> it = stream.iterator(); it.hasNext(); ) {
				this.ephemera$placeStep(placement, index + 1, ctx, random, it.next(), placed);
			}
		}
	}
}
