package com.ephemera.render.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.mixin.client.particle.CameraDepthAccessor;
import com.ephemera.mixin.client.particle.FrustumAccessor;
import com.ephemera.mixin.client.particle.ParticleAccessor;
import com.ephemera.mixin.client.particle.SingleQuadParticleAccessor;
import java.util.Arrays;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Quaternionf;

/** Primitive coordinates throughout the hot path: no streams, boxed doubles, or temporary boxes. */
public final class ParticleCullingManager {
    private static final long[] LIGHT_KEYS = new long[4096], LIGHT_EPOCHS = new long[4096];
    private static final int[] LIGHT_VALUES = new int[4096];
    private static final int[] LIGHT_XS = new int[4096], LIGHT_YS = new int[4096], LIGHT_ZS = new int[4096];
    private static final SingleQuadParticle.Layer[] SHEETS = new SingleQuadParticle.Layer[64];
    private static final int[] SHEET_COUNTS = new int[64];
    private static final BlockPos.MutableBlockPos LIGHT_POS = new BlockPos.MutableBlockPos();
    private static final ThreadLocal<Quaternionf> ROTATION = ThreadLocal.withInitial(Quaternionf::new);
    private static long lightEpoch = 1;
    private static boolean extracting;
    // Only inherited vanilla quad extraction has the size contract used for the envelope.
    private static final ClassValue<Boolean> STANDARD_QUAD = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if (!type.getName().startsWith("net.minecraft.client.particle.")) return false;
            try {
                for (Class<?> c = type; c != null && c != SingleQuadParticle.class; c = c.getSuperclass()) {
                    for (var method : c.getDeclaredMethods()) if (method.getName().equals("extractRotatedQuad")) return false;
                }
                return type.getMethod("extract", net.minecraft.client.renderer.state.level.QuadParticleRenderState.class,
                        Camera.class, float.class).getDeclaringClass() == SingleQuadParticle.class;
            } catch (ReflectiveOperationException e) { return false; }
        }
    };
    private ParticleCullingManager() { }
    public static void beginFrame() { extracting = true; lightEpoch++; Arrays.fill(SHEET_COUNTS, 0); }
    public static void endFrame() { extracting = false; Arrays.fill(SHEETS, null); }
    public static void invalidateLight() { lightEpoch++; }
    public static Quaternionf rotation(SingleQuadParticle particle) {
        return EphemeraCommon.config().particleCulling && STANDARD_QUAD.get(particle.getClass())
                ? ROTATION.get().identity() : new Quaternionf();
    }
    public static boolean shouldExtract(SingleQuadParticle particle, Frustum frustum, Camera camera, float tick) {
        if (!EphemeraCommon.config().particleCulling || !STANDARD_QUAD.get(particle.getClass())) return true;
        ParticleAccessor p = (ParticleAccessor) particle;
        double x = p.ephemera$xo() + (p.ephemera$x() - p.ephemera$xo()) * tick;
        double y = p.ephemera$yo() + (p.ephemera$y() - p.ephemera$yo()) * tick;
        double z = p.ephemera$zo() + (p.ephemera$z() - p.ephemera$zo()) * tick;
        double radius = Math.abs(particle.getQuadSize(tick)) * 1.415 + .125;
        if (!Double.isFinite(x + y + z + radius)) return true;
        // A depth-distance rejection uses the actual camera far plane, never an arbitrary range.
        double depth = (x - camera.position().x) * camera.forwardVector().x()
                + (y - camera.position().y) * camera.forwardVector().y()
                + (z - camera.position().z) * camera.forwardVector().z();
        if (camera.getCapturedFrustum() == null && !camera.isPanoramicMode()
                && depth > ((CameraDepthAccessor) camera).ephemera$depthFar() + radius * 3 + 16) return false;
        int result = ((FrustumAccessor) frustum).ephemera$cube(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        if (result != -1 && result != -2) return false;
        if (EphemeraCommon.config().particleOcclusionCulling && EntityOcclusionManager.boxOccluded(
                x - radius, y - radius, z - radius, x + radius, y + radius, z + radius)) return false;
        return admit(((SingleQuadParticleAccessor) particle).ephemera$layer());
    }
    private static boolean admit(SingleQuadParticle.Layer layer) {
        int limit = EphemeraCommon.config().maxParticlesPerSheet;
        if (limit <= 0) return true;
        for (int i = 0; i < SHEETS.length; i++) {
            if (SHEETS[i] == null) SHEETS[i] = layer;
            if (SHEETS[i] == layer) return SHEET_COUNTS[i]++ < limit;
        }
        return true; // Unknown sheets beyond the fixed table retain vanilla behavior.
    }
    public static int light(ClientLevel level, double x, double y, double z) {
        if (!EphemeraCommon.config().particleLightmapCache || !Minecraft.getInstance().isSameThread()) {
            BlockPos pos = BlockPos.containing(x, y, z);
            return level.hasChunkAt(pos) ? LightCoordsUtil.getLightCoords(level, pos) : 15728640;
        }
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        long key = BlockPos.asLong(bx, by, bz);
        int s = (int) (key ^ key >>> 31 ^ key >>> 47) & 4095;
        if (extracting && level == Minecraft.getInstance().level && LIGHT_EPOCHS[s] == lightEpoch && LIGHT_KEYS[s] == key
                && LIGHT_XS[s] == bx && LIGHT_YS[s] == by && LIGHT_ZS[s] == bz) return LIGHT_VALUES[s];
        LIGHT_POS.set(bx, by, bz);
        int value = level.hasChunkAt(LIGHT_POS) ? LightCoordsUtil.getLightCoords(level, LIGHT_POS) : 15728640;
        if (extracting && level == Minecraft.getInstance().level) {
            LIGHT_KEYS[s] = key; LIGHT_EPOCHS[s] = lightEpoch; LIGHT_VALUES[s] = value;
            LIGHT_XS[s] = bx; LIGHT_YS[s] = by; LIGHT_ZS[s] = bz;
        }
        return value;
    }
}
