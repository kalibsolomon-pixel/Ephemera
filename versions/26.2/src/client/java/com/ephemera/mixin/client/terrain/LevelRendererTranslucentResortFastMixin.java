package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.diagnostics.MixinHealth;
import com.ephemera.render.terrain.SectionMeshRenderCache;
import com.ephemera.render.terrain.SectionMeshSwapHook;
import com.ephemera.render.terrain.TerrainTopology;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Eliminates per-frame {@link BlockPos}, {@link TranslucencyPointOfView}, and
 * {@code ObjectListIterator} allocations in {@link LevelRenderer#scheduleTranslucentSectionResort},
 * rejects non-translucent sections before reading {@code AtomicReference<SectionMesh>} or computing
 * {@code TranslucencyPointOfView}, and skips redundant sweeps in O(1) once all visible sections
 * have been checked for the current camera block position and terrain topology.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTranslucentResortFastMixin {
	// NOTE: Do NOT reference ChunkSectionLayer in a static field initializer on LevelRenderer!
	// On MC 26.3, RenderPipelines.<clinit> loads LevelRenderer before SOLID_TERRAIN /
	// CUTOUT_TERRAIN / TRANSLUCENT_TERRAIN are assigned; touching ChunkSectionLayer here would
	// trigger ChunkSectionLayer.<clinit> prematurely and leave its RenderPipeline fields null.
	@Unique private static final boolean EPHEMERA$SWAP_HOOK =
		SectionMeshSwapHook.class.isAssignableFrom(SectionRenderDispatcher.RenderSection.class);

	@Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
	@Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbyVisibleSections;
	@Shadow private BlockPos lastTranslucentSortBlockPos;
	@Shadow private int translucencyResortIterationIndex;

	@Unique private final TranslucencyPointOfView ephemera$pov = new TranslucencyPointOfView();
	@Unique private long ephemera$lastResortTopology = Long.MIN_VALUE;
	@Unique private long ephemera$lastResortVisGen = Long.MIN_VALUE;
	@Unique private int ephemera$sweptCount;
	@Unique private boolean ephemera$resortSwept;
	// Content fingerprint of the list at the start of the current sweep cycle: rotation-only
	// frames refill visibleSections with identical content but bump the epoch, which would
	// otherwise reset the round-robin sweep every frame and never let it reach steady state.
	@Unique private long ephemera$lastSweptDigest1;
	@Unique private long ephemera$lastSweptDigest2;
	@Unique private int ephemera$lastSweptDigestSize = -1;

	@Unique
	private static boolean ephemera$resortDigestActive() {
		return MixinHealth.isApplied("SectionOcclusionGraphDigestMixin")
			&& MixinHealth.isApplied("TerrainLevelRendererChunkBatchMixin")
			&& !MixinHealth.hasFailed("SectionOcclusionGraphDigestMixin");
	}

	@WrapMethod(method = "scheduleTranslucentSectionResort")
	private void ephemera$fastScheduleTranslucentSectionResort(Vec3 cameraPos, Operation<Void> original) {
		if (!EphemeraCommon.config().terrainFastFrustumAndGrid) {
			this.ephemera$resortSwept = false;
			original.call(cameraPos);
			return;
		}
		int visSize = this.visibleSections.size();
		if (visSize == 0) {
			return;
		}
		int bx = Mth.floor(cameraPos.x);
		int by = Mth.floor(cameraPos.y);
		int bz = Mth.floor(cameraPos.z);
		BlockPos lastPos = this.lastTranslucentSortBlockPos;
		boolean changedBlockPos = lastPos == null || lastPos.getX() != bx || lastPos.getY() != by || lastPos.getZ() != bz;
		long topo = TerrainTopology.topology();
		long visGen = TerrainTopology.visibleSections();

		// When the digest mixins are healthy, compare list CONTENT instead of the per-refill
		// epoch: rotation-only frames refill identical content and must not reset the sweep.
		boolean digestActive = ephemera$resortDigestActive();
		boolean listUnchanged = digestActive
			? TerrainTopology.visibleSectionsDigest1 == this.ephemera$lastSweptDigest1
				&& TerrainTopology.visibleSectionsDigest2 == this.ephemera$lastSweptDigest2
				&& TerrainTopology.visibleSectionsDigestSize == this.ephemera$lastSweptDigestSize
				&& visSize == this.ephemera$lastSweptDigestSize
			: visGen == this.ephemera$lastResortVisGen;

		if (!changedBlockPos && this.ephemera$resortSwept
			&& topo == this.ephemera$lastResortTopology
			&& listUnchanged) {
			return;
		}
		if (changedBlockPos || topo != this.ephemera$lastResortTopology || !listUnchanged) {
			this.ephemera$resortSwept = false;
			this.ephemera$sweptCount = 0;
			this.ephemera$lastResortTopology = topo;
			this.ephemera$lastResortVisGen = visGen;
			this.ephemera$lastSweptDigest1 = TerrainTopology.visibleSectionsDigest1;
			this.ephemera$lastSweptDigest2 = TerrainTopology.visibleSectionsDigest2;
			this.ephemera$lastSweptDigestSize = digestActive ? visSize : -1;
		}

		TranslucencyPointOfView pov = this.ephemera$pov;
		boolean useHook = EPHEMERA$SWAP_HOOK;

		Object[] nearby = this.nearbyVisibleSections.elements();
		for (int i = 0, n = this.nearbyVisibleSections.size(); i < n; i++) {
			ephemera$checkSection((SectionRenderDispatcher.RenderSection) nearby[i], pov, cameraPos, changedBlockPos, true, useHook);
		}

		int idx = this.translucencyResortIterationIndex % visSize;
		int steps = Math.max(visSize / 8, 15);
		Object[] visible = this.visibleSections.elements();
		for (int remaining = steps; remaining > 0; remaining--) {
			SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) visible[idx];
			idx++;
			if (idx >= visSize) {
				idx = 0;
			}
			ephemera$checkSection(section, pov, cameraPos, changedBlockPos, false, useHook);
		}
		this.translucencyResortIterationIndex = idx;

		if (changedBlockPos) {
			this.lastTranslucentSortBlockPos = new BlockPos(bx, by, bz);
			this.ephemera$sweptCount = 0;
		} else {
			int swept = this.ephemera$sweptCount + steps;
			if (swept >= visSize) {
				this.ephemera$sweptCount = visSize;
				this.ephemera$resortSwept = true;
			} else {
				this.ephemera$sweptCount = swept;
			}
		}
	}

	@Unique
	private static void ephemera$checkSection(
		SectionRenderDispatcher.RenderSection section,
		TranslucencyPointOfView pov,
		Vec3 cameraPos,
		boolean changedBlockPos,
		boolean nearby,
		boolean useHook
	) {
		if (useHook) {
			SectionMeshRenderCache cache = ((SectionMeshSwapHook) section).ephemera$compiledCache();
			if (cache == null || cache.ephemera$draw(ChunkSectionLayer.TRANSLUCENT.ordinal()) == null) {
				return;
			}
		} else if (!section.hasTranslucentGeometry()) {
			return;
		}
		if (section.transparencyResortingScheduled()) {
			return;
		}
		pov.set(cameraPos, section.getSectionNode());
		boolean changedPov = section.getSectionMesh().isDifferentPointOfView(pov);
		boolean forceAxisOrNearby = changedBlockPos && (pov.isAxisAligned() || nearby);
		if ((forceAxisOrNearby || changedPov) && section.hasTranslucentGeometry()) {
			section.resortTransparency();
		}
	}
}
