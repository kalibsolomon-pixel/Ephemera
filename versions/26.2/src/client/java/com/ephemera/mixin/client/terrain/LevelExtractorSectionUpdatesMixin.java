package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.pacing.EphemeraPacerRuntime;
import com.ephemera.render.terrain.TerrainTopology;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterators;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two complementary optimizations for the {@code sectionUpdates} loop in {@link LevelExtractor#extract}:
 * <ol>
 *   <li><b>O(1) steady-frame skip</b>: when no {@code SectionDirtyState} has become dirty and
 *       {@code visibleSections} is unchanged since the last clean scan, skips both
 *       {@code new RenderRegionCache()} and the 2,000+ element {@code visibleSections} walk.</li>
 *   <li><b>Non-starving burst cap</b>: bounds extreme non-player {@code createRegion} spikes
 *       (teleports, rapid camera whips, render-distance changes) without starving background
 *       chunk-builder workers (at least {@value #EPHEMERA$MIN_NON_PLAYER_PER_FRAME} sections or
 *       {@value #EPHEMERA$UNCAPPED_BURST_BUDGET_NS} ns; tighter when an explicit FPS cap is set).
 *       Player-driven block edits ({@code isDirtyFromPlayer()}) always bypass the cap.</li>
 * </ol>
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorSectionUpdatesMixin {
	@Unique private static final int EPHEMERA$MIN_NON_PLAYER_PER_FRAME = 24;
	@Unique private static final long EPHEMERA$UNCAPPED_BURST_BUDGET_NS = 3_000_000L; // 3.0 ms ceiling
	@Unique private static final RenderRegionCache EPHEMERA$EMPTY_CACHE = new RenderRegionCache();

	@Shadow @Final private LevelRenderer levelRenderer;
	@Shadow private @Nullable SectionUpdateTracker sectionUpdateTracker;

	@Unique private @Nullable SectionUpdateTracker ephemera$lastCleanTracker;
	@Unique private long ephemera$lastCleanDirtyEpoch = Long.MIN_VALUE;
	@Unique private long ephemera$lastCleanVisEpoch = Long.MIN_VALUE;
	@Unique private int ephemera$lastCleanVisSize = -1;

	@WrapOperation(
		method = "extract",
		at = @At(value = "NEW", target = "()Lnet/minecraft/client/renderer/chunk/RenderRegionCache;")
	)
	private RenderRegionCache ephemera$checkCleanAndSkipCacheAlloc(
		Operation<RenderRegionCache> original,
		@Share("ephemera$skipScan") LocalBooleanRef skipScan,
		@Share("ephemera$dirtyEpochBefore") LocalLongRef dirtyEpochBefore,
		@Share("ephemera$visEpochBefore") LocalLongRef visEpochBefore,
		@Share("ephemera$visSizeBefore") LocalIntRef visSizeBefore,
		@Share("ephemera$sawDirty") LocalBooleanRef sawDirty,
		@Share("ephemera$secStartNs") LocalLongRef startNs,
		@Share("ephemera$secCount") LocalIntRef count,
		@Share("ephemera$secBudgetNs") LocalLongRef budgetNs
	) {
		long dEpoch = TerrainTopology.dirtySections();
		long vEpoch = TerrainTopology.visibleSections();
		int vSize = this.levelRenderer.visibleSections().size();
		dirtyEpochBefore.set(dEpoch);
		visEpochBefore.set(vEpoch);
		visSizeBefore.set(vSize);
		sawDirty.set(false);
		startNs.set(0L);
		count.set(0);

		if (EphemeraCommon.config().terrainFastFrustumAndGrid
			&& this.sectionUpdateTracker != null
			&& this.sectionUpdateTracker == this.ephemera$lastCleanTracker
			&& dEpoch == this.ephemera$lastCleanDirtyEpoch
			&& vEpoch == this.ephemera$lastCleanVisEpoch
			&& vSize == this.ephemera$lastCleanVisSize) {
			skipScan.set(true);
			budgetNs.set(Long.MAX_VALUE);
			return EPHEMERA$EMPTY_CACHE;
		}
		skipScan.set(false);

		if (EphemeraCommon.config().framePacerAdaptiveUploadBudget) {
			int cappedFps = EphemeraPacerRuntime.budget().effectiveTargetFps();
			if (cappedFps > 0) {
				budgetNs.set(Math.min(EphemeraPacerRuntime.currentUploadBudgetNs(), EPHEMERA$UNCAPPED_BURST_BUDGET_NS));
			} else {
				budgetNs.set(EPHEMERA$UNCAPPED_BURST_BUDGET_NS);
			}
		} else {
			budgetNs.set(Long.MAX_VALUE);
		}
		return original.call();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	@WrapOperation(
		method = "extract",
		at = @At(
			value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;iterator()Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"
		)
	)
	private ObjectListIterator ephemera$skipCleanVisibleWalk(
		ObjectArrayList list,
		Operation<ObjectListIterator> original,
		@Share("ephemera$skipScan") LocalBooleanRef skipScan
	) {
		if (skipScan.get()) {
			return ObjectIterators.EMPTY_ITERATOR;
		}
		return original.call(list);
	}

	@WrapOperation(
		method = "extract",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/SectionUpdateTracker$SectionDirtyState;isDirty()Z")
	)
	private boolean ephemera$budgetNonPlayerSectionSnapshots(
		SectionUpdateTracker.SectionDirtyState state,
		Operation<Boolean> original,
		@Share("ephemera$sawDirty") LocalBooleanRef sawDirty,
		@Share("ephemera$secStartNs") LocalLongRef startNs,
		@Share("ephemera$secCount") LocalIntRef count,
		@Share("ephemera$secBudgetNs") LocalLongRef budgetNs
	) {
		boolean dirty = original.call(state);
		if (!dirty) {
			return false;
		}
		sawDirty.set(true);
		long budget = budgetNs.get();
		if (budget == Long.MAX_VALUE || state.isDirtyFromPlayer()) {
			return true;
		}
		if (count.get() >= EPHEMERA$MIN_NON_PLAYER_PER_FRAME) {
			long start = startNs.get();
			if (start != 0L && (System.nanoTime() - start) >= budget) {
				return false;
			}
		}
		return true;
	}

	@WrapOperation(
		method = "extract",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/RenderRegionCache;createRegion(Lnet/minecraft/client/multiplayer/ClientLevel;J)Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"
		)
	)
	private RenderSectionRegion ephemera$trackRegionSnapshot(
		RenderRegionCache cache,
		ClientLevel level,
		long sectionNode,
		Operation<RenderSectionRegion> original,
		@Share("ephemera$secStartNs") LocalLongRef startNs,
		@Share("ephemera$secCount") LocalIntRef count
	) {
		if (startNs.get() == 0L) {
			startNs.set(System.nanoTime());
		}
		count.set(count.get() + 1);
		return original.call(cache, level, sectionNode);
	}

	@Inject(
		method = "extract",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")
	)
	private void ephemera$recordCleanScan(
		CallbackInfo ci,
		@Share("ephemera$skipScan") LocalBooleanRef skipScan,
		@Share("ephemera$sawDirty") LocalBooleanRef sawDirty,
		@Share("ephemera$dirtyEpochBefore") LocalLongRef dirtyEpochBefore,
		@Share("ephemera$visEpochBefore") LocalLongRef visEpochBefore,
		@Share("ephemera$visSizeBefore") LocalIntRef visSizeBefore
	) {
		if (skipScan.get()) {
			return;
		}
		if (!sawDirty.get() && TerrainTopology.dirtySections() == dirtyEpochBefore.get()) {
			this.ephemera$lastCleanTracker = this.sectionUpdateTracker;
			this.ephemera$lastCleanDirtyEpoch = dirtyEpochBefore.get();
			this.ephemera$lastCleanVisEpoch = visEpochBefore.get();
			this.ephemera$lastCleanVisSize = visSizeBefore.get();
		} else {
			this.ephemera$lastCleanTracker = null;
		}
	}
}
