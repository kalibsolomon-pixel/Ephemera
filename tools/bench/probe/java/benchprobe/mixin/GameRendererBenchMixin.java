package benchprobe.mixin;

import benchprobe.BenchProbeClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public final class GameRendererBenchMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void ephemeraBench$onRenderEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		BenchProbeClient.onRenderFrameEnd();
	}
}
