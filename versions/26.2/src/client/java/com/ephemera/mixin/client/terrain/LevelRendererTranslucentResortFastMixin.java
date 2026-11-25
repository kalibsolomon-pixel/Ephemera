package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
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
	@Unique private static final int EPHEMERA$TRANSLUCENT_ORD = ChunkSectionLayer.TRANSLUCENT.ordinal();
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

		if (!changedBlockPos && this.ephemera$resortSwept
			&& topo == this.ephemera$lastResortTopology
			&& visGen == this.ephemera$lastResortVisGen) {
			return;
		}
		if (changedBlockPos || topo != this.ephemera$lastResortTopology || visGen != this.ephemera$lastResortVisGen) {
			this.ephemera$resortSwept = false;
			this.ephemera$sweptCount = 0;
			this.ephemera$lastResortTopology = topo;
			this.ephemera$lastResortVisGen = visGen;
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
			if (cache == null || cache.ephemera$draw(EPHEMERA$TRANSLUCENT_ORD) == null) {
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
