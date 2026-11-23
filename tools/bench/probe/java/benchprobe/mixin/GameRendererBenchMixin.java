package benchprobe.mixin;

import benchprobe.BenchProbeClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public final class GameRendererBenchMixin {
	/** Arguments omitted so one hook matches 26.2 {@code render(DeltaTracker, boolean)} and 26.3 {@code render()}. */
	@Inject(method = "render", at = @At("HEAD"))
	private void ephemeraBench$onRenderStart(CallbackInfo ci) {
		BenchProbeClient.onRenderFrameStart();
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void ephemeraBench$onRenderEnd(CallbackInfo ci) {
		BenchProbeClient.onRenderFrameEnd();
	}
}
