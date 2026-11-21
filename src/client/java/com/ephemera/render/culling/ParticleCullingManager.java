package com.ephemera.render.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.mixin.client.particle.CameraDepthAccessor;
import com.ephemera.mixin.client.particle.FrustumAccessor;
import com.ephemera.mixin.client.particle.ParticleAccessor;
import com.ephemera.mixin.client.particle.SingleQuadParticleAccessor;
import com.ephemera.render.terrain.FrustumFastCube;
import com.mojang.blaze3d.systems.RenderSystem;
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
    private static final Quaternionf RENDER_ROTATION = new Quaternionf();
    private static final ThreadLocal<Quaternionf> ROTATION = ThreadLocal.withInitial(Quaternionf::new);
    private static volatile Thread renderThread;
    private static long lightEpoch = 1;
    private static boolean extracting;
    private static boolean frameCulling;
    private static boolean frameOcclusion;
    private static boolean frameOcclusionReady;
    private static boolean frameLightmapCache;
    private static int frameMaxPerSheet;
    private static ClientLevel frameLevel;
    private static Camera frameCamera;
    private static double frameCamX, frameCamY, frameCamZ;
    private static float frameFwdX, frameFwdY, frameFwdZ;
    private static boolean frameDepthCheck;
    private static float frameDepthFar;
    private static Class<?> lastQuadClass;
    private static boolean lastQuadStandard;
    private static SingleQuadParticle.Layer lastSheetLayer;
    private static int lastSheetIdx;
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
    public static void beginFrame() { beginFrame(null); }
    public static void beginFrame(Camera camera) {
        extracting = true;
        lightEpoch++;
        Arrays.fill(SHEET_COUNTS, 0);
        lastSheetLayer = null;
        lastSheetIdx = -1;
        var cfg = EphemeraCommon.config();
        frameCulling = cfg.particleCulling;
        frameOcclusion = cfg.particleOcclusionCulling;
        frameLightmapCache = cfg.particleLightmapCache;
        frameMaxPerSheet = cfg.maxParticlesPerSheet;
        frameLevel = Minecraft.getInstance().level;
        frameCamera = camera;
        if (camera != null) {
            var pos = camera.position();
            frameCamX = pos.x; frameCamY = pos.y; frameCamZ = pos.z;
            var fwd = camera.forwardVector();
            frameFwdX = fwd.x(); frameFwdY = fwd.y(); frameFwdZ = fwd.z();
            frameDepthCheck = camera.getCapturedFrustum() == null && !camera.isPanoramicMode();
            frameDepthFar = frameDepthCheck ? ((CameraDepthAccessor) camera).ephemera$depthFar() : 0f;
            frameOcclusionReady = frameOcclusion && EntityOcclusionManager.isReadyForParticles(camera);
        } else {
            frameDepthCheck = false;
            frameOcclusionReady = false;
        }
    }
    public static void endFrame() {
        extracting = false;
        frameCamera = null;
        frameLevel = null;
        frameOcclusionReady = false;
        Arrays.fill(SHEETS, null);
    }
    public static void invalidateLight() { lightEpoch++; }
    public static boolean isCullingEnabled() {
        return extracting ? frameCulling : EphemeraCommon.config().particleCulling;
    }
    private static boolean isStandardQuad(Class<?> cls) {
        if (cls == lastQuadClass) return lastQuadStandard;
        boolean standard = STANDARD_QUAD.get(cls);
        lastQuadClass = cls;
        lastQuadStandard = standard;
        return standard;
    }
    public static Quaternionf rotation(SingleQuadParticle particle) {
        if (!isCullingEnabled() || !isStandardQuad(particle.getClass())) {
            return new Quaternionf();
        }
        Thread cur = Thread.currentThread();
        if (cur == renderThread) {
            return RENDER_ROTATION.identity();
        }
        if (renderThread == null && RenderSystem.isOnRenderThread()) {
            renderThread = cur;
            return RENDER_ROTATION.identity();
        }
        return ROTATION.get().identity();
    }
    public static boolean shouldExtract(SingleQuadParticle particle, Frustum frustum, Camera camera, float tick) {
        boolean fastFrame = extracting && camera == frameCamera;
        if (fastFrame) {
            if (!frameCulling || !isStandardQuad(particle.getClass())) return true;
        } else if (!EphemeraCommon.config().particleCulling || !isStandardQuad(particle.getClass())) {
            return true;
        }
        ParticleAccessor p = (ParticleAccessor) particle;
        double x = p.ephemera$xo() + (p.ephemera$x() - p.ephemera$xo()) * tick;
        double y = p.ephemera$yo() + (p.ephemera$y() - p.ephemera$yo()) * tick;
        double z = p.ephemera$zo() + (p.ephemera$z() - p.ephemera$zo()) * tick;
        double radius = Math.abs(particle.getQuadSize(tick)) * 1.415 + .125;
        if (!Double.isFinite(x + y + z + radius)) return true;
        // A depth-distance rejection uses the actual camera near/far planes, never an arbitrary range.
        if (fastFrame) {
            if (frameDepthCheck) {
                double depth = (x - frameCamX) * frameFwdX + (y - frameCamY) * frameFwdY + (z - frameCamZ) * frameFwdZ;
                if (depth < -radius || depth > frameDepthFar + radius * 3 + 16) return false;
            }
        } else if (camera.getCapturedFrustum() == null && !camera.isPanoramicMode()) {
            var pos = camera.position();
            var fwd = camera.forwardVector();
            double depth = (x - pos.x) * fwd.x() + (y - pos.y) * fwd.y() + (z - pos.z) * fwd.z();
            if (depth < -radius || depth > ((CameraDepthAccessor) camera).ephemera$depthFar() + radius * 3 + 16) return false;
        }
        // 1-pass branchless 6-plane cube intersection via FrustumFastCube (zero point+AABB double work).
        if (frustum instanceof FrustumFastCube fastCube) {
            if (!fastCube.ephemera$intersectsCube(x, y, z, radius)) return false;
        } else if (!frustum.pointInFrustum(x, y, z)) {
            int result = ((FrustumAccessor) frustum).ephemera$cube(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
            if (result != -1 && result != -2) return false;
        }
        if (fastFrame) {
            if (frameOcclusionReady && EntityOcclusionManager.particleOccludedFast(x, y, z, radius)) return false;
            return admit(((SingleQuadParticleAccessor) particle).ephemera$layer(), frameMaxPerSheet);
        }
        if (EphemeraCommon.config().particleOcclusionCulling
                && EntityOcclusionManager.particleOccluded(x, y, z, radius)) return false;
        return admit(((SingleQuadParticleAccessor) particle).ephemera$layer(), EphemeraCommon.config().maxParticlesPerSheet);
    }
    private static boolean admit(SingleQuadParticle.Layer layer, int limit) {
        if (limit <= 0) return true;
        if (layer == lastSheetLayer && lastSheetIdx >= 0) {
            return SHEET_COUNTS[lastSheetIdx]++ < limit;
        }
        for (int i = 0; i < SHEETS.length; i++) {
            if (SHEETS[i] == null) SHEETS[i] = layer;
            if (SHEETS[i] == layer) {
                lastSheetLayer = layer;
                lastSheetIdx = i;
                return SHEET_COUNTS[i]++ < limit;
            }
        }
        return true; // Unknown sheets beyond the fixed table retain vanilla behavior.
    }
    public static int light(ClientLevel level, double x, double y, double z) {
        if (extracting && level == frameLevel) {
            if (!frameLightmapCache) {
                BlockPos pos = BlockPos.containing(x, y, z);
                return level.hasChunkAt(pos) ? LightCoordsUtil.getLightCoords(level, pos) : 15728640;
            }
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            long key = BlockPos.asLong(bx, by, bz);
            int s = (int) (key ^ key >>> 31 ^ key >>> 47) & 4095;
            if (LIGHT_EPOCHS[s] == lightEpoch && LIGHT_KEYS[s] == key
                    && LIGHT_XS[s] == bx && LIGHT_YS[s] == by && LIGHT_ZS[s] == bz) return LIGHT_VALUES[s];
            LIGHT_POS.set(bx, by, bz);
            int value = level.hasChunkAt(LIGHT_POS) ? LightCoordsUtil.getLightCoords(level, LIGHT_POS) : 15728640;
            LIGHT_KEYS[s] = key; LIGHT_EPOCHS[s] = lightEpoch; LIGHT_VALUES[s] = value;
            LIGHT_XS[s] = bx; LIGHT_YS[s] = by; LIGHT_ZS[s] = bz;
            return value;
        }
        if (!EphemeraCommon.config().particleLightmapCache || !Minecraft.getInstance().isSameThread()) {
            BlockPos pos = BlockPos.containing(x, y, z);
            return level.hasChunkAt(pos) ? LightCoordsUtil.getLightCoords(level, pos) : 15728640;
        }
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        LIGHT_POS.set(bx, by, bz);
        return level.hasChunkAt(LIGHT_POS) ? LightCoordsUtil.getLightCoords(level, LIGHT_POS) : 15728640;
    }
}
