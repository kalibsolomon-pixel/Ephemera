package com.ephemera.mixin.common;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.network.FastVarInt;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Branchless lookup-table VarInt sizing and packed multi-byte Netty buffer writes.
 */
@Mixin(VarInt.class)
abstract class VarIntFastMixin {
	@WrapMethod(method = "getByteSize")
	private static int ephemera$fastGetByteSize(int value, Operation<Integer> original) {
		return EphemeraCommon.config().networkFastVarInt
			? FastVarInt.varIntByteSize(value)
			: original.call(value);
	}

	@WrapMethod(method = "write")
	private static ByteBuf ephemera$fastWrite(ByteBuf buf, int value, Operation<ByteBuf> original) {
		if (!EphemeraCommon.config().networkFastVarInt) {
			return original.call(buf, value);
		}
		if ((value & (0xFFFFFFFF << 7)) == 0) {
			buf.writeByte(value);
		} else if ((value & (0xFFFFFFFF << 14)) == 0) {
			buf.writeShort(FastVarInt.packVarInt2(value));
		} else if ((value & (0xFFFFFFFF << 21)) == 0) {
			buf.writeMedium(FastVarInt.packVarInt3(value));
		} else if ((value & (0xFFFFFFFF << 28)) == 0) {
			buf.writeInt(FastVarInt.packVarInt4(value));
		} else {
			buf.writeInt(FastVarInt.packVarInt5Prefix(value));
			buf.writeByte(value >>> 28);
		}
		return buf;
	}
}
