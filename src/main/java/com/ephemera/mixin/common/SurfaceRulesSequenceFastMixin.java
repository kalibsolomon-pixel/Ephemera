package com.ephemera.mixin.common;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces {@code ImmutableList.iterator()} allocation inside {@code SurfaceRules.SequenceRule#tryApply}
 * (measured at ~885 MB / 25s across chunk generation workers in JFR) with a flat array index loop.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRule")
abstract class SurfaceRulesSequenceFastMixin {
	@Shadow @Final private List<SurfaceRules.SurfaceRule> rules;
	@Unique private SurfaceRules.SurfaceRule[] ephemera$rulesArray;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$cacheArray(List<SurfaceRules.SurfaceRule> rules, CallbackInfo ci) {
		this.ephemera$rulesArray = rules.toArray(SurfaceRules.SurfaceRule[]::new);
	}

	/**
	 * @author Ephemera
	 * @reason Zero-allocation indexed array scan replaces ImmutableList.iterator() per column/block.
	 */
	@Overwrite
	public @Nullable BlockState tryApply(int x, int y, int z) {
		SurfaceRules.SurfaceRule[] arr = this.ephemera$rulesArray;
		for (int i = 0, n = arr.length; i < n; i++) {
			BlockState state = arr[i].tryApply(x, y, z);
			if (state != null) {
				return state;
			}
		}
		return null;
	}
}
