package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.pacing.FrameBudgetPacer;
import com.ephemera.render.pacing.EphemeraPacerRuntime;
import com.ephemera.render.terrain.SectionUpdateTrackerFrameCache;
import com.ephemera.render.terrain.TerrainTopology;
import com.ephemera.render.terrain.vulkan.VulkanMeshTerrainEngine;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterators;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
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
 *   <li><b>Unified single-deadline streaming cap</b>: shares one per-frame deadline with Vulkan
 *       mesh uploads via {@link EphemeraPacerRuntime#currentStreamingDeadlineNs()}, prioritizing
 *       player-driven block edits (unconditional bypass) and nearby sections ({@code <= 2} sections
 *       from camera) while preventing non-player snapshot bursts from blowing the frame budget.</li>
 * </ol>
 * Uses plain primitive instance fields (render-thread-only, non-reentrant) rather than MixinExtras
 * {@code @Share} local-ref wrappers so C2 inlines {@code budgetNonPlayerSectionSnapshots} cleanly
 * into {@code extract}'s hot visible-section loop with zero per-frame allocations.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorSectionUpdatesMixin {
	@Unique private static final int EPHEMERA$NEAR_SECTION_RADIUS = 2;
	@Unique private static final int EPHEMERA$NEAR_FLOOR_CAPPED = 2;
	@Unique private static final int EPHEMERA$NEAR_FLOOR_UNCAPPED = 4;
	@Unique private static final RenderRegionCache EPHEMERA$EMPTY_CACHE = new RenderRegionCache();

	@Shadow @Final private LevelRenderer levelRenderer;
	@Shadow private @Nullable SectionUpdateTracker sectionUpdateTracker;

	@Unique private @Nullable SectionUpdateTracker ephemera$lastCleanTracker;
	@Unique private long ephemera$lastCleanDirtyEpoch = Long.MIN_VALUE;
	@Unique private long ephemera$lastCleanVisEpoch = Long.MIN_VALUE;
	@Unique private int ephemera$lastCleanVisSize = -1;

	@Unique private boolean ephemera$skipScan;
	@Unique private long ephemera$dirtyEpochBefore;
	@Unique private long ephemera$visEpochBefore;
	@Unique private int ephemera$visSizeBefore;
	@Unique private boolean ephemera$sawDirty;
	@Unique private boolean ephemera$streamingBudgetActive;
	@Unique private boolean ephemera$reserveVulkanUploadSlice;
	@Unique private int ephemera$nearPriorityFloor;
	@Unique private int ephemera$camSectionX;
	@Unique private int ephemera$camSectionY;
	@Unique private int ephemera$camSectionZ;

	@WrapOperation(
		method = "extract",
		at = @At(value = "NEW", target = "()Lnet/minecraft/client/renderer/chunk/RenderRegionCache;")
	)
	private RenderRegionCache ephemera$checkCleanAndSkipCacheAlloc(
		Operation<RenderRegionCache> original,
		@Local(argsOnly = true) Camera camera
	) {
		long dEpoch = TerrainTopology.dirtySections();
		long vEpoch = TerrainTopology.visibleSections();
		int vSize = this.levelRenderer.visibleSections().size();
		this.ephemera$dirtyEpochBefore = dEpoch;
		this.ephemera$visEpochBefore = vEpoch;
		this.ephemera$visSizeBefore = vSize;
		this.ephemera$sawDirty = false;

		if (EphemeraCommon.config().terrainFastFrustumAndGrid
			&& this.sectionUpdateTracker != null
			&& this.sectionUpdateTracker == this.ephemera$lastCleanTracker
			&& dEpoch == this.ephemera$lastCleanDirtyEpoch
			&& vEpoch == this.ephemera$lastCleanVisEpoch
			&& vSize == this.ephemera$lastCleanVisSize) {
			this.ephemera$skipScan = true;
			this.ephemera$streamingBudgetActive = false;
			return EPHEMERA$EMPTY_CACHE;
		}
		this.ephemera$skipScan = false;
		if (this.sectionUpdateTracker instanceof SectionUpdateTrackerFrameCache frameCache) {
			frameCache.ephemera$beginExtractFrame();
		}

		BlockPos camPos = camera != null ? camera.blockPosition() : BlockPos.ZERO;
		this.ephemera$camSectionX = SectionPos.blockToSectionCoord(camPos.getX());
		this.ephemera$camSectionY = SectionPos.blockToSectionCoord(camPos.getY());
		this.ephemera$camSectionZ = SectionPos.blockToSectionCoord(camPos.getZ());

		if (EphemeraPacerRuntime.beginFrameStreamingBudget()) {
			this.ephemera$streamingBudgetActive = true;
			this.ephemera$reserveVulkanUploadSlice = VulkanMeshTerrainEngine.get().isAvailable();
			int cappedFps = EphemeraPacerRuntime.budget().effectiveTargetFps();
			this.ephemera$nearPriorityFloor = cappedFps > 0 ? EPHEMERA$NEAR_FLOOR_CAPPED : EPHEMERA$NEAR_FLOOR_UNCAPPED;
		} else {
			this.ephemera$streamingBudgetActive = false;
			this.ephemera$reserveVulkanUploadSlice = false;
			this.ephemera$nearPriorityFloor = EPHEMERA$NEAR_FLOOR_UNCAPPED;
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
	private ObjectListIterator ephemera$skipCleanVisibleWalk(ObjectArrayList list, Operation<ObjectListIterator> original) {
		if (this.ephemera$skipScan) {
			return ObjectIterators.EMPTY_ITERATOR;
		}
		return original.call(list);
	}

	@ModifyExpressionValue(
		method = "extract",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/SectionUpdateTracker$SectionDirtyState;isDirty()Z")
	)
	private boolean ephemera$budgetNonPlayerSectionSnapshots(
		boolean dirty,
		@Local SectionRenderDispatcher.RenderSection section,
		@Local SectionUpdateTracker.SectionDirtyState state
	) {
		if (!dirty) {
			return false;
		}
		this.ephemera$sawDirty = true;
		if (!this.ephemera$streamingBudgetActive || state.isDirtyFromPlayer()) {
			return true;
		}
		FrameBudgetPacer pacer = EphemeraPacerRuntime.budget();
		if (pacer.hasSnapshotStreamingHeadroom(this.ephemera$reserveVulkanUploadSlice)) {
			return true;
		}
		long node = section.getSectionNode();
		boolean nearby = Math.abs(SectionPos.x(node) - this.ephemera$camSectionX) <= EPHEMERA$NEAR_SECTION_RADIUS
			&& Math.abs(SectionPos.y(node) - this.ephemera$camSectionY) <= EPHEMERA$NEAR_SECTION_RADIUS
			&& Math.abs(SectionPos.z(node) - this.ephemera$camSectionZ) <= EPHEMERA$NEAR_SECTION_RADIUS;
		int floor = nearby ? this.ephemera$nearPriorityFloor : 1;
		return pacer.allowSnapshotStreamingWork(floor, this.ephemera$reserveVulkanUploadSlice);
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
		Operation<RenderSectionRegion> original
	) {
		if (!this.ephemera$streamingBudgetActive) {
			return original.call(cache, level, sectionNode);
		}
		long t0 = System.nanoTime();
		RenderSectionRegion region = original.call(cache, level, sectionNode);
		EphemeraPacerRuntime.budget().recordStreamingWork(System.nanoTime() - t0);
		return region;
	}

	@Inject(
		method = "extract",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")
	)
	private void ephemera$recordCleanScan(CallbackInfo ci) {
		if (this.ephemera$skipScan) {
			return;
		}
		if (this.sectionUpdateTracker instanceof SectionUpdateTrackerFrameCache frameCache) {
			frameCache.ephemera$endExtractFrame();
		}
		if (!this.ephemera$sawDirty && TerrainTopology.dirtySections() == this.ephemera$dirtyEpochBefore) {
			this.ephemera$lastCleanTracker = this.sectionUpdateTracker;
			this.ephemera$lastCleanDirtyEpoch = this.ephemera$dirtyEpochBefore;
			this.ephemera$lastCleanVisEpoch = this.ephemera$visEpochBefore;
			this.ephemera$lastCleanVisSize = this.ephemera$visSizeBefore;
		} else {
			this.ephemera$lastCleanTracker = null;
		}
	}
}
