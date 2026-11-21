package com.ephemera.mixin.client.terrain;

import com.ephemera.render.terrain.SectionMeshSwapHook;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

/**
 * Eliminates per-frame {@code PoseStack} and {@code ObjectListIterator} allocations plus
 * 3,000+ {@code AtomicReference} / {@code CompiledSectionMesh} / {@code List} cache misses
 * in {@link LevelExtractor#extractVisibleBlockEntities} by checking the mirrored 1-byte
 * {@code ephemera$hasBlockEntities} flag directly on each visible {@code RenderSection}.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorBlockEntityFastMixin {
	@Shadow @Final private LevelRenderer levelRenderer;
	@Shadow private ClientLevel level;

	@Unique private static final boolean EPHEMERA$SWAP_HOOK =
		SectionMeshSwapHook.class.isAssignableFrom(SectionRenderDispatcher.RenderSection.class);

	@Unique private final PoseStack ephemera$crumblingPoseStack = new PoseStack();

	/**
	 * @author Ephemera
	 * @reason Direct array scan with 1-byte block-entity presence filter and reused PoseStack.
	 */
	@Overwrite
	private void extractVisibleBlockEntities(Camera camera, float partialTick, LevelRenderState levelRenderState) {
		Vec3 camPos = camera.position();
		double camX = camPos.x();
		double camY = camPos.y();
		double camZ = camPos.z();
		ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = this.levelRenderer.visibleSections();
		Object[] elements = visible.elements();
		int size = visible.size();
		boolean useHook = EPHEMERA$SWAP_HOOK;
		BlockEntityRenderDispatcher dispatcher = this.levelRenderer.blockEntityRenderDispatcher();
		List<BlockEntityRenderState> outStates = levelRenderState.blockEntityRenderStates;
		Long2ObjectMap<SortedSet<BlockDestructionProgress>> destruction = this.level.destructionProgress();
		boolean hasDestruction = !destruction.isEmpty();
		long now = 0L;

		for (int i = 0; i < size; i++) {
			SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
			if (useHook && !((SectionMeshSwapHook) section).ephemera$hasBlockEntities()) {
				continue;
			}
			List<BlockEntity> blockEntities = section.getSectionMesh().getRenderableBlockEntities();
			int beCount = blockEntities.size();
			if (beCount == 0) {
				continue;
			}
			if (now == 0L) {
				now = Util.getMillis();
			}
			if (useHook) {
				if (now < ((SectionMeshSwapHook) section).ephemera$fadeEndMs() && section.getVisibility(now) < 0.3f) {
					continue;
				}
			} else if (section.getVisibility(now) < 0.3f) {
				continue;
			}
			for (int j = 0; j < beCount; j++) {
				BlockEntity blockEntity = blockEntities.get(j);
				ModelFeatureRenderer.CrumblingOverlay overlay = null;
				if (hasDestruction) {
					BlockPos pos = blockEntity.getBlockPos();
					SortedSet<BlockDestructionProgress> progressSet = destruction.get(pos.asLong());
					if (progressSet != null && !progressSet.isEmpty()) {
						PoseStack ps = this.ephemera$crumblingPoseStack;
						ps.pushPose();
						ps.translate((double) pos.getX() - camX, (double) pos.getY() - camY, (double) pos.getZ() - camZ);
						overlay = new ModelFeatureRenderer.CrumblingOverlay(progressSet.last().getProgress(), ps.last());
						ps.popPose();
					}
				}
				BlockEntityRenderState state = dispatcher.tryExtractRenderState(blockEntity, partialTick, overlay, false);
				if (state != null) {
					outStates.add(state);
				}
			}
		}

		Set<BlockEntity> globalEntities = this.level.getGloballyRenderedBlockEntities();
		if (!globalEntities.isEmpty()) {
			Iterator<BlockEntity> it = globalEntities.iterator();
			while (it.hasNext()) {
				BlockEntity be = it.next();
				if (be.isRemoved()) {
					it.remove();
				} else {
					BlockEntityRenderState state = dispatcher.tryExtractRenderState(be, partialTick, null, true);
					if (state != null) {
						outStates.add(state);
					}
				}
			}
		}
	}
}
