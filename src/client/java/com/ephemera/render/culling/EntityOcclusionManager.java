package com.ephemera.render.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.culling.DirectMappedSweepCache;
import com.ephemera.core.culling.OpaqueLayerSweep;
import com.ephemera.mixin.client.culling.EntityRendererAccessor;
import com.ephemera.mixin.client.culling.LeashDataAccessor;
import com.ephemera.mixin.client.particle.FrustumAccessor;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/** Render-thread owner of all occlusion proofs. Unknown or unavailable data always fails open. */
public final class EntityOcclusionManager {
    private static final DirectMappedSweepCache ENTITIES = new DirectMappedSweepCache(1024);
    private static final DirectMappedSweepCache BLOCK_ENTITIES = new DirectMappedSweepCache(512);
    private static final DirectMappedSweepCache BOX_QUERIES = new DirectMappedSweepCache(1024);
    private static final int SIZE = 8192;
    private static final long[] BLOCK_KEYS = new long[SIZE], BLOCK_STAMPS = new long[SIZE], BLOCK_FRAMES = new long[SIZE];
    private static final int[] BLOCK_YS = new int[SIZE];
    private static final boolean[] BLOCK_SOLID = new boolean[SIZE];
    private static final OpaqueLayerSweep SWEEP = new OpaqueLayerSweep(EntityOcclusionManager::isOpaque);
    private static final IntOpenHashSet LEASH_HOLDERS = new IntOpenHashSet();
    private static final IntOpenHashSet PACKET_HOLDERS = new IntOpenHashSet();
    private static ClientLevel level;
    private static LevelChunkSection lastSection;
    private static int lastSx, lastSy, lastSz;
    private static boolean sectionValid, frameActive, cameraValid;
    private static long terrainStamp = 1, frame = 1, now;
    private static double cameraX, cameraY, cameraZ;
    private static Frustum frustum;
    private static int occludedEntities, occludedBlockEntities, visibleSections;
    private static final ClassValue<Boolean> STANDARD_SHADOW = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != MobRenderer.class && c != EntityRenderer.class; c = c.getSuperclass()) {
                for (var method : c.getDeclaredMethods()) if (method.getName().equals("getShadowRadius")) return false;
            }
            return true;
        }
    };
    private EntityOcclusionManager() { }

    public static void beginFrame(Camera camera) {
        ClientLevel current = Minecraft.getInstance().level;
        if (level != current) reset(current);
        cameraX = camera.position().x; cameraY = camera.position().y; cameraZ = camera.position().z;
        frustum = camera.getCullFrustum();
        frameActive = true; cameraValid = current != null;
        frame++; now = System.nanoTime();
        occludedEntities = occludedBlockEntities = 0;
        releaseSection();
    }
    public static void endFrame(int renderedSections) {
        visibleSections = renderedSections;
        frameActive = false; frame++; releaseSection(); ParticleCullingManager.endFrame();
    }
    private static void releaseSection() { lastSection = null; sectionValid = false; }
    public static void terrainChanged() {
        terrainStamp++;
        releaseSection();
        // Every mutation invalidates the geometry proof, including cached visible results.
        ParticleCullingManager.invalidateLight();
    }
    public static void reset(ClientLevel current) {
        level = current; cameraValid = false; frustum = null; frameActive = false;
        terrainChanged(); ENTITIES.clear(); BLOCK_ENTITIES.clear(); BOX_QUERIES.clear();
        FastShadowCache.invalidate();
        LEASH_HOLDERS.clear(); PACKET_HOLDERS.clear();
        occludedEntities = occludedBlockEntities = visibleSections = 0;
    }
    public static void trackLeashes(ClientLevel current) {
        LEASH_HOLDERS.clear();
        if (!EphemeraCommon.config().entityOcclusionCulling && !EphemeraCommon.config().blockStateCulling) {
            PACKET_HOLDERS.clear(); return;
        }
        LEASH_HOLDERS.addAll(PACKET_HOLDERS);
        PACKET_HOLDERS.clear();
        for (Entity entity : current.entitiesForRendering()) {
            if (entity instanceof Leashable leashable && leashable.getLeashData() != null) {
                Leashable.LeashData data = leashable.getLeashData();
                if (data.leashHolder != null) LEASH_HOLDERS.add(data.leashHolder.getId());
                else {
                    int holder = ((LeashDataAccessor) (Object) data).ephemera$delayedHolder();
                    if (holder != 0) LEASH_HOLDERS.add(holder);
                }
            }
        }
    }
    public static void leashPacket(int holder) {
        if (holder != 0) { LEASH_HOLDERS.add(holder); PACKET_HOLDERS.add(holder); }
    }
    public static boolean excluded(Entity entity) {
        return entity.hasCustomName() || entity.isCurrentlyGlowing() || entity.hasGlowingTag()
                || entity.getTeam() != null || entity.shouldShowName() || entity.belowNameDisplay() != null
                || entity instanceof FishingHook || entity instanceof EnderDragon || entity instanceof WitherBoss
                || entity instanceof Guardian || entity instanceof Frog
                || entity.isPassenger() || entity.isVehicle() || LEASH_HOLDERS.contains(entity.getId())
                || entity instanceof Leashable leashable && leashable.getLeashData() != null;
    }
    public static boolean cullEntity(Entity entity, EntityRenderer<?, ?> renderer) {
        if (!EphemeraCommon.config().entityOcclusionCulling || !ready() || excluded(entity)
                || entity.level() != level || !(entity instanceof Mob || entity instanceof ItemEntity
                || entity instanceof ItemFrame || entity instanceof Painting)
                || !renderer.getClass().getName().startsWith("net.minecraft.")) return false;
        AABB box = ((EntityRendererAccessor) renderer).ephemera$cullingBox(entity);
        if (box.maxX - box.minX > 8 || box.maxY - box.minY > 8 || box.maxZ - box.minZ > 8) return false;
        double dx = entity.xOld - entity.getX(), dy = entity.yOld - entity.getY(), dz = entity.zOld - entity.getZ();
        double pad = entity instanceof Mob mob ? Math.max(1, mob.getScale() * 2) : 1;
        double x0 = box.minX + Math.min(0, dx) - pad, y0 = box.minY + Math.min(0, dy) - pad;
        double z0 = box.minZ + Math.min(0, dz) - pad, x1 = box.maxX + Math.max(0, dx) + pad;
        double y1 = box.maxY + Math.max(0, dy) + pad, z1 = box.maxZ + Math.max(0, dz) + pad;
        if (Minecraft.getInstance().options.entityShadows().get()) {
            // Vanilla EntityRenderer/MobRenderer contracts, inspected from 26.2 bytecode. Unknown
            // radius overrides use the engine's absolute 32-block cap instead of an estimate.
            float radius = 32;
            if (STANDARD_SHADOW.get(renderer.getClass())) {
                radius = ((EntityRendererAccessor) renderer).ephemera$shadowRadius();
                if (entity instanceof Mob mob) radius = radius * mob.getScale() * mob.getAgeScale();
                radius = Math.min(32, radius);
            }
            if (!Float.isFinite(radius)) return false;
            double extent = Math.max(0, radius) + 1;
            x0 = Math.min(x0, Math.min(entity.xOld, entity.getX()) - extent);
            y0 = Math.min(y0, Math.min(entity.yOld, entity.getY()) - extent);
            z0 = Math.min(z0, Math.min(entity.zOld, entity.getZ()) - extent);
            x1 = Math.max(x1, Math.max(entity.xOld, entity.getX()) + extent);
            z1 = Math.max(z1, Math.max(entity.zOld, entity.getZ()) + extent);
        }
        boolean hidden = cached(ENTITIES, entity.getId(), x0, y0, z0, x1, y1, z1);
        if (hidden) occludedEntities++;
        return hidden;
    }
    public static boolean cullBlockEntity(BlockEntity entity, boolean global, BlockEntityRenderer<?, ?> renderer) {
        if (!EphemeraCommon.config().blockEntityOcclusionCulling || global || !ready()
                || entity.getLevel() != level || entity instanceof BeaconBlockEntity
                || level.getGloballyRenderedBlockEntities().contains(entity) || renderer == null
                || renderer.shouldRenderOffScreen() || !renderer.getClass().getName().startsWith("net.minecraft.")) return false;
        // These vanilla renderers fit inside this envelope, including double chest lids and sign text.
        if (!(entity instanceof ChestBlockEntity || entity instanceof EnderChestBlockEntity
                || entity instanceof SignBlockEntity || entity instanceof ShulkerBoxBlockEntity
                || entity instanceof BannerBlockEntity)
                || !entity.getClass().getName().startsWith("net.minecraft.")) return false;
        BlockPos p = entity.getBlockPos();
        boolean hidden = cached(BLOCK_ENTITIES, p.asLong(), p.getX() - 1, p.getY() - 1, p.getZ() - 1,
                p.getX() + 2, p.getY() + 2, p.getZ() + 2);
        if (hidden) occludedBlockEntities++;
        return hidden;
    }
    private static boolean ready() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!cameraValid || level == null || level != minecraft.level || !minecraft.isSameThread()) return false;
        var current = minecraft.gameRenderer.mainCamera().position();
        return cameraX == current.x && cameraY == current.y && cameraZ == current.z;
    }
    private static int lower(double v) { return (int) Math.floor(v * 4); }
    private static int upper(double v) { return (int) Math.ceil(v * 4); }
    private static boolean cached(DirectMappedSweepCache cache, long id,
                                  double x0, double y0, double z0, double x1, double y1, double z1) {
        if (!finite(x0, y0, z0, x1, y1, z1)) return false;
        int cx = lower(cameraX), cy = lower(cameraY), cz = lower(cameraZ);
        int ax = lower(x0), ay = lower(y0), az = lower(z0), bx = upper(x1), by = upper(y1), bz = upper(z1);
        long time = frameActive ? now : System.nanoTime();
        int result = cache.lookup(id, terrainStamp, time, cx, cy, cz, ax, ay, az, bx, by, bz);
        if (result != DirectMappedSweepCache.MISS) return result == DirectMappedSweepCache.OCCLUDED;
        boolean hidden = sweep(cx, cy, cz, ax * .25, ay * .25, az * .25, bx * .25, by * .25, bz * .25);
        cache.put(id, terrainStamp, time, cx, cy, cz, ax, ay, az, bx, by, bz, hidden);
        return hidden;
    }
    private static boolean finite(double a, double b, double c, double d, double e, double f) {
        return Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(c) && Double.isFinite(d)
                && Double.isFinite(e) && Double.isFinite(f) && a <= d && b <= e && c <= f
                && Math.max(Math.max(Math.abs(a), Math.abs(d)), Math.max(Math.abs(c), Math.abs(f))) < 30_000_064;
    }
    public static boolean boxOccluded(double x0, double y0, double z0, double x1, double y1, double z1) {
        if (!ready() || !finite(x0, y0, z0, x1, y1, z1)) return false;
        // Equal quantized bounds share the proof across particles, weather and API consumers.
        // Complete bounds remain in the key, so this hash can only cause eviction.
        long id = (long) lower(x0 - .125) * 73428767L ^ (long) lower(y0 - .125) * 912931L
                ^ (long) lower(z0 - .125) * 438289L;
        try { return cached(BOX_QUERIES, id, x0 - .125, y0 - .125, z0 - .125, x1 + .125, y1 + .125, z1 + .125); }
        finally { if (!frameActive) releaseSection(); }
    }
    private static boolean sweep(int cx, int cy, int cz, double x0, double y0, double z0, double x1, double y1, double z1) {
        try {
            return SWEEP.isOccluded(cx * .25 - .125, cy * .25 - .125, cz * .25 - .125,
                    cx * .25 + .375, cy * .25 + .375, cz * .25 + .375, x0, y0, z0, x1, y1, z1);
        } finally { releaseSection(); }
    }
    public static boolean isOpaque(int x, int y, int z) {
        if (!ready() || level.isOutsideBuildHeight(y)) return false;
        long key = BlockPos.asLong(x, y, z);
        int s = (int) (key ^ (key >>> 27) ^ (key >>> 43)) & (SIZE - 1);
        if (BLOCK_FRAMES[s] == frame && BLOCK_STAMPS[s] == terrainStamp && BLOCK_KEYS[s] == key && BLOCK_YS[s] == y) return BLOCK_SOLID[s];
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        if (!sectionValid || sx != lastSx || sy != lastSy || sz != lastSz) {
            LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
            lastSection = chunk == null ? null : chunk.getSection(level.getSectionIndex(y));
            lastSx = sx; lastSy = sy; lastSz = sz; sectionValid = true;
        }
        boolean solid = false;
        if (lastSection != null && !lastSection.hasOnlyAir()) {
            BlockState state = lastSection.getBlockState(x & 15, y & 15, z & 15);
            // Static solid render shapes only. Glass, leaves, fluids and partial blocks never occlude.
            solid = state.isSolidRender() && state.canOcclude() && !state.getBlock().hasDynamicShape()
                    && !state.hasOffsetFunction();
        }
        BLOCK_KEYS[s] = key; BLOCK_YS[s] = y; BLOCK_STAMPS[s] = terrainStamp; BLOCK_FRAMES[s] = frame; BLOCK_SOLID[s] = solid;
        if (!frameActive) releaseSection();
        return solid;
    }
    public static long terrainStamp() { return terrainStamp; }
    public static boolean sectionVisible(int x, int y, int z) {
        if (!ready() || frustum == null) return true;
        double x0 = x * 16.0, y0 = y * 16.0, z0 = z * 16.0;
        if (!finite(x0, y0, z0, x0 + 16, y0 + 16, z0 + 16)) return true;
        int result = ((FrustumAccessor) frustum).ephemera$cube(x0, y0, z0, x0 + 16, y0 + 16, z0 + 16);
        return (result == -1 || result == -2) && !boxOccluded(x0, y0, z0, x0 + 16, y0 + 16, z0 + 16);
    }
    public static int visibleSections() { return visibleSections; }
    public static int occludedEntities() { return occludedEntities; }
    public static int occludedBlockEntities() { return occludedBlockEntities; }
    public static Frustum frustum() { return frustum; }
    public static boolean frameActive() { return frameActive; }
}
