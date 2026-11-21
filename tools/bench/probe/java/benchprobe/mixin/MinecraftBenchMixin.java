package benchprobe.mixin;

import benchprobe.BenchProbeClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public final class MinecraftBenchMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void ephemeraBench$onTick(CallbackInfo ci) {
		BenchProbeClient.onClientTick();
	}
}
