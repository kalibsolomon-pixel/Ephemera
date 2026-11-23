package com.ephemera.mixin.client.visuals;

import com.ephemera.visuals.HeldWorldLightTracker;
import com.ephemera.visuals.VisualsRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.Std140Builder;
import java.nio.ByteBuffer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Extends {@link GlobalSettingsUniform} by two std140 {@code vec4}s ({@code EphemeraHeldLightPos}
 * and {@code EphemeraHeldLightParam}) at the tail of the {@code Globals} UBO so standard OpenGL
 * and Vulkan {@code core/terrain} shaders can sample Ephemera's optional held-item world light
 * without adding descriptor bindings or modifying unpatched shaders.
 *
 * <p>Stands down automatically for Sodium, VulkanMod, Canvas, Nvidium, and dynamic-lighting mods.
 */
@Mixin(GlobalSettingsUniform.class)
public abstract class GlobalSettingsUniformMixin {
	@ModifyArg(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/GpuDevice;createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/blaze3d/buffers/GpuBuffer;"
		),
		index = 2
	)
	private long ephemera$expandGlobalsBuffer(long vanillaSize) {
		return ((vanillaSize + 15L) & ~15L) + 32L;
	}

	@ModifyArg(
		method = "update",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/buffers/Std140Builder;onStack(Lorg/lwjgl/system/MemoryStack;I)Lcom/mojang/blaze3d/buffers/Std140Builder;"
		),
		index = 1
	)
	private int ephemera$expandGlobalsBuilder(int vanillaSize) {
		return ((vanillaSize + 15) & ~15) + 32;
	}

	@WrapOperation(
		method = "update",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/buffers/Std140Builder;get()Ljava/nio/ByteBuffer;"
		)
	)
	private ByteBuffer ephemera$appendHeldWorldLight(
		Std140Builder builder,
		Operation<ByteBuffer> original,
		@Local(argsOnly = true) DeltaTracker deltaTracker,
		@Local(argsOnly = true) Vec3 cameraPos
	) {
		if (!VisualsRuntime.heldWorldLight()) {
			builder.putVec4(0.0f, 0.0f, 0.0f, 0.0f);
			builder.putVec4(0.0f, 0.0f, 0.0f, 0.0f);
			return original.call(builder);
		}
		float partialTick = deltaTracker != null ? deltaTracker.getGameTimeDeltaPartialTick(false) : 1.0f;
		HeldWorldLightTracker.sampleForFrame(cameraPos, partialTick);
		builder.putVec4(
			HeldWorldLightTracker.relX(),
			HeldWorldLightTracker.relY(),
			HeldWorldLightTracker.relZ(),
			HeldWorldLightTracker.invRadius()
		);
		builder.putVec4(HeldWorldLightTracker.blockLight240(), 0.0f, 0.0f, 0.0f);
		return original.call(builder);
	}
}
