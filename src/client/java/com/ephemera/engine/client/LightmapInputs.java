package com.ephemera.engine.client;

import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3fc;

/** Exact shader-uniform bit comparison, including mutable vector components. */
public final class LightmapInputs {
    private static volatile long shaderGeneration;
    private long generation;
    private final int[] bits = new int[18];
    private boolean valid;

    public boolean matches(LightmapRenderState state) {
        return valid && generation == shaderGeneration && visit(state, false);
    }

    public void capture(LightmapRenderState state) {
        visit(state, true);
        generation = shaderGeneration;
        valid = true;
    }

    public void invalidate() {
        valid = false;
    }

    public static void shadersReloaded() {
        shaderGeneration++;
    }

    private boolean visit(LightmapRenderState s, boolean write) {
        return scalar(0, s.blockFactor, write) && scalar(1, s.skyFactor, write)
            && scalar(2, s.brightness, write) && scalar(3, s.darknessEffectScale, write)
            && scalar(4, s.nightVisionEffectIntensity, write) && scalar(5, s.bossOverlayWorldDarkening, write)
            && vector(6, s.blockLightTint, write) && vector(9, s.skyLightColor, write)
            && vector(12, s.ambientColor, write) && vector(15, s.nightVisionColor, write);
    }

    private boolean vector(int i, Vector3fc value, boolean write) {
        return scalar(i, value.x(), write) && scalar(i + 1, value.y(), write) && scalar(i + 2, value.z(), write);
    }

    private boolean scalar(int i, float value, boolean write) {
        int raw = Float.floatToRawIntBits(value);
        if (write) bits[i] = raw;
        return bits[i] == raw;
    }
}
