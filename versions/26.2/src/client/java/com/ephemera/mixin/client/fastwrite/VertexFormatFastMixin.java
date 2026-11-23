package com.ephemera.mixin.client.fastwrite;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eliminates repeated linear {@code Object2ObjectArrayMap} string scans ({@code Objects.equals})
 * inside {@code BufferBuilder.<init>} by caching the 7 standard immutable vertex element lookups
 * directly on {@link VertexFormat} using O(1) interned-reference identity checks.
 */
@Mixin(VertexFormat.class)
abstract class VertexFormatFastMixin {
	@Shadow @Final private Map<String, VertexFormatElement> elements;
	@Shadow @Final private int vertexSize;

	@Unique private VertexFormatElement ephemera$pos;
	@Unique private VertexFormatElement ephemera$color;
	@Unique private VertexFormatElement ephemera$uv0;
	@Unique private VertexFormatElement ephemera$uv1;
	@Unique private VertexFormatElement ephemera$uv2;
	@Unique private VertexFormatElement ephemera$uv3;
	@Unique private VertexFormatElement ephemera$normal;
	@Unique private VertexFormatElement ephemera$lineWidth;
	@Unique private int ephemera$elementsHash;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void ephemera$cacheStandardElements(CallbackInfo ci) {
		ephemera$pos = elements.get("Position");
		ephemera$color = elements.get("Color");
		ephemera$uv0 = elements.get("UV0");
		ephemera$uv1 = elements.get("UV1");
		ephemera$uv2 = elements.get("UV2");
		ephemera$uv3 = elements.get("UV3");
		ephemera$normal = elements.get("Normal");
		ephemera$lineWidth = elements.get("LineWidth");
		ephemera$elementsHash = elements.hashCode();
	}

	@Inject(method = "equals(Ljava/lang/Object;)Z", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastEquals(Object other, CallbackInfoReturnable<Boolean> cir) {
		if (other == (Object) this) {
			cir.setReturnValue(true);
			return;
		}
		if (!(other instanceof VertexFormat)) {
			cir.setReturnValue(false);
			return;
		}
		VertexFormatFastMixin o = (VertexFormatFastMixin) other;
		if (this.vertexSize != o.vertexSize || this.ephemera$elementsHash != o.ephemera$elementsHash) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "contains(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastContains(String name, CallbackInfoReturnable<Boolean> cir) {
		if (name == "Position") { cir.setReturnValue(ephemera$pos != null); return; }
		if (name == "Color") { cir.setReturnValue(ephemera$color != null); return; }
		if (name == "UV0") { cir.setReturnValue(ephemera$uv0 != null); return; }
		if (name == "UV1") { cir.setReturnValue(ephemera$uv1 != null); return; }
		if (name == "UV2") { cir.setReturnValue(ephemera$uv2 != null); return; }
		if (name == "UV3") { cir.setReturnValue(ephemera$uv3 != null); return; }
		if (name == "Normal") { cir.setReturnValue(ephemera$normal != null); return; }
		if (name == "LineWidth") { cir.setReturnValue(ephemera$lineWidth != null); }
	}

	@Inject(method = "getElement(Ljava/lang/String;)Lcom/mojang/blaze3d/vertex/VertexFormatElement;", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastGetElement(String name, CallbackInfoReturnable<VertexFormatElement> cir) {
		if (name == "Position") { cir.setReturnValue(ephemera$pos); return; }
		if (name == "Color") { cir.setReturnValue(ephemera$color); return; }
		if (name == "UV0") { cir.setReturnValue(ephemera$uv0); return; }
		if (name == "UV1") { cir.setReturnValue(ephemera$uv1); return; }
		if (name == "UV2") { cir.setReturnValue(ephemera$uv2); return; }
		if (name == "UV3") { cir.setReturnValue(ephemera$uv3); return; }
		if (name == "Normal") { cir.setReturnValue(ephemera$normal); return; }
		if (name == "LineWidth") { cir.setReturnValue(ephemera$lineWidth); }
	}
}
