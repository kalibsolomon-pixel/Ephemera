package com.ephemera.mixin.client.compat;

import com.ephemera.render.fastwrite.FastGlyphWriter;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.network.chat.Style;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Cooperative fast-write bridge for Aurora Client's SDF text glyph renderer ({@code SdfInstance}).
 * Applied only when {@code aurora} is loaded and fail-soft ({@code require = 0}).
 * Emits all 1–4 quads of each SDF glyph (shadow + bold + main) in a single native buffer reservation,
 * compatible with both Aurora 3.6.0 and Aurora 3.7.0+.
 */
@Pseudo
@Mixin(targets = "com.aurora.client.ui.text.SdfInstance", remap = false)
public abstract class AuroraSdfInstanceFastMixin {
	@Unique
	private static final MethodHandle EPHEMERA_CELL_GETTER = ephemera$resolveCellGetter();

	@Shadow(remap = false) private float penX;
	@Shadow(remap = false) private float penY;
	@Shadow(remap = false) private float boldOffset;
	@Shadow(remap = false) private float shadowOffset;
	@Shadow(remap = false) private float quadLeft;
	@Shadow(remap = false) private float quadTop;
	@Shadow(remap = false) private float quadRight;
	@Shadow(remap = false) private float quadBottom;
	@Shadow(remap = false) private Style style;
	@Shadow(remap = false) private int color;
	@Shadow(remap = false) private int shadowColor;

	@Unique
	private static MethodHandle ephemera$resolveCellGetter() {
		try {
			Class<?> cls = Class.forName("com.aurora.client.ui.text.SdfInstance", false,
				FastGlyphWriter.class.getClassLoader());
			Field f = cls.getDeclaredField("cell");
			f.setAccessible(true);
			return MethodHandles.lookup().unreflectGetter(f)
				.asType(MethodType.methodType(Object.class, Object.class));
		} catch (Throwable ignored) {
			return null;
		}
	}

	@Inject(method = "render(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZ)V",
		at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private void ephemera$fastSdfRender(Matrix4fc pose, VertexConsumer out, int light, boolean noDepth, CallbackInfo ci) {
		if (EPHEMERA_CELL_GETTER == null) {
			return;
		}
		Object rawCell;
		try {
			rawCell = EPHEMERA_CELL_GETTER.invokeExact((Object) this);
		} catch (Throwable ignored) {
			return;
		}
		if (rawCell instanceof FastGlyphWriter.SdfCell cell
				&& FastGlyphWriter.writeSdfGlyph(out, pose, light,
					this.penX, this.penY, this.quadLeft, this.quadTop, this.quadRight, this.quadBottom,
					this.boldOffset, this.shadowOffset, this.color, this.shadowColor,
					this.style.isBold(), this.style.isItalic(),
					cell.ephemera$u0(), cell.ephemera$u1(), cell.ephemera$v0(), cell.ephemera$v1())) {
			ci.cancel();
		}
	}
}
