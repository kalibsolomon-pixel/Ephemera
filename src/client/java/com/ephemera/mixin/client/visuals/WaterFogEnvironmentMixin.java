package com.ephemera.mixin.client.visuals;

import com.ephemera.visuals.VisualsRuntime;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Underwater clarity: stretches the water fog distance so the sea floor stays readable. */
@Mixin(WaterFogEnvironment.class)
public abstract class WaterFogEnvironmentMixin {
	@Inject(method = "setupFog", at = @At("TAIL"))
	private void ephemera$underwaterClarity(FogData data, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!VisualsRuntime.underwaterClarity()) {
			return;
		}
		float scale = VisualsRuntime.UNDERWATER_CLARITY_SCALE;
		data.environmentalEnd *= scale;
		data.skyEnd *= scale;
		data.cloudEnd *= scale;
	}
}
