package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.network.FastVarInt;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.VarLong;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Branchless lookup-table VarLong byte-size calculation.
 */
@Mixin(VarLong.class)
abstract class VarLongFastMixin {
	@WrapMethod(method = "getByteSize")
	private static int ephemera$fastGetByteSize(long value, Operation<Integer> original) {
		return EphemeraCommon.config().networkFastVarInt
			? FastVarInt.varLongByteSize(value)
			: original.call(value);
	}
}
