package com.ephemera.mixin.client.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.render.culling.EntityOcclusionManager;
import com.ephemera.render.culling.ParticleCullingManager;
import com.ephemera.render.pacing.EphemeraPacerRuntime;
import java.util.Deque;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Coordinates targeted occlusion invalidation and time-sliced {@link ClientLevel#pollLightUpdates()}
 * draining so mass light-queue spikes (teleports, chunk rebuild bursts, fast flight) never stall
 * the render thread by draining 1,000+ queued runnables in a single frame.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelCullingMixin {
	@Unique private static final long EPHEMERA$LIGHT_DRAIN_MAX_NS = 1_200_000L; // 1.2 ms cap per frame
	@Unique private static final int EPHEMERA$LIGHT_DRAIN_MIN_ITEMS = 16;

	@Shadow @Final private Deque<Runnable> lightUpdateQueue;

	@Inject(method = "setBlocksDirty", at = @At("HEAD"))
	private void ephemera$blocksDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		if (!EntityOcclusionManager.sameOcclusionOpacity(oldState, newState)) {
			EntityOcclusionManager.blockChanged(pos.getX(), pos.getY(), pos.getZ());
		}
	}

	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void ephemera$blockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		if (!EntityOcclusionManager.sameOcclusionOpacity(oldState, newState)) {
			EntityOcclusionManager.blockChanged(pos.getX(), pos.getY(), pos.getZ());
		}
	}

	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
	private void ephemera$serverBlockState(BlockPos pos, BlockState newState, int flags, CallbackInfo ci) {
		BlockState oldState = ((ClientLevel) (Object) this).getBlockState(pos);
		if (!EntityOcclusionManager.sameOcclusionOpacity(oldState, newState)) {
			EntityOcclusionManager.blockChanged(pos.getX(), pos.getY(), pos.getZ());
		}
	}

	@Inject(method = "onChunkLoaded", at = @At("HEAD"))
	private void ephemera$chunkLoaded(ChunkPos pos, CallbackInfo ci) {
		EntityOcclusionManager.chunkChanged(pos.x(), pos.z());
	}

	@Inject(method = "unload", at = @At("HEAD"))
	private void ephemera$chunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
		ChunkPos pos = chunk.getPos();
		EntityOcclusionManager.chunkChanged(pos.x(), pos.z());
	}

	@Inject(method = "pollLightUpdates", at = @At("HEAD"), cancellable = true)
	private void ephemera$pacedLightDrain(CallbackInfo ci) {
		int size = this.lightUpdateQueue.size();
		if (size == 0) {
			ci.cancel();
			return;
		}
		ParticleCullingManager.invalidateLight();
		if (!EphemeraCommon.config().framePacerAdaptiveUploadBudget || size <= 48) {
			return;
		}
		// Vanilla drains the ENTIRE queue in one frame once size >= 1000 (causing 15-35 ms spikes).
		// Time-slice large bursts against the frame's streaming deadline with a guaranteed floor.
		long startNs = System.nanoTime();
		long streamingDeadline = EphemeraPacerRuntime.currentStreamingDeadlineNs();
		long deadlineNs = Math.min(startNs + EPHEMERA$LIGHT_DRAIN_MAX_NS, streamingDeadline);
		int target = size < 1000 ? Math.max(10, size / 10) : Math.min(size, 256);
		int done = 0;
		while (done < target) {
			Runnable r = this.lightUpdateQueue.poll();
			if (r == null) {
				break;
			}
			r.run();
			done++;
			if (done >= EPHEMERA$LIGHT_DRAIN_MIN_ITEMS && (done & 7) == 0 && System.nanoTime() >= deadlineNs) {
				break;
			}
		}
		ci.cancel();
	}

	@Inject(method = "tickEntities", at = @At("RETURN"))
	private void ephemera$leashes(CallbackInfo ci) {
		EntityOcclusionManager.trackLeashes((ClientLevel) (Object) this);
	}
}
