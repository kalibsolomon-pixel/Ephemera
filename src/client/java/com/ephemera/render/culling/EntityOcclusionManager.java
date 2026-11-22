package com.ephemera.render.culling;

import com.ephemera.EphemeraCommon;
import com.ephemera.visuals.VisualsRuntime;
import com.ephemera.core.culling.DirectMappedSweepCache;
import com.ephemera.core.culling.OpaqueLayerSweep;
import com.ephemera.core.culling.CullingBounds;
import com.ephemera.core.culling.ParticleCellOcclusion;
import com.ephemera.mixin.client.culling.EntityRendererAccessor;
import com.ephemera.platform.RenderPlatform;
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
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Render-thread owner of all occlusion proofs. Unknown or unavailable data always fails open. */
public final class EntityOcclusionManager {
    private static final DirectMappedSweepCache ENTITIES = new DirectMappedSweepCache(DirectMappedSweepCache.ENTITY_SLOTS);
    private static final DirectMappedSweepCache BLOCK_ENTITIES = new DirectMappedSweepCache(DirectMappedSweepCache.BLOCK_ENTITY_SLOTS);
    private static final DirectMappedSweepCache BOX_QUERIES = new DirectMappedSweepCache(1024);
    private static final SectionBlockCache SECTIONS = new SectionBlockCache();
    private static final CullingBounds BOUNDS = new CullingBounds();
    private static final OpaqueLayerSweep SWEEP = new OpaqueLayerSweep(EntityOcclusionManager::isOpaqueUnchecked);
    private static final ParticleCellOcclusion PARTICLES = new ParticleCellOcclusion(EntityOcclusionManager::isOpaqueUnchecked);
    private static final IntOpenHashSet LEASH_HOLDERS = new IntOpenHashSet();
    private static final IntOpenHashSet PACKET_HOLDERS = new IntOpenHashSet();
    private static ClientLevel level;
    private static boolean frameActive, cameraValid, entityShadowsEnabled, hasGlobalBlockEntities;
    private static long terrainStamp = 1, frame = 1, now;
    private static double cameraX, cameraY, cameraZ;
    private static int camCellX, camCellY, camCellZ;
    private static Vec3 lastCamPos;
    private static Thread renderThread;
    private static Frustum frustum;
    private static int occludedEntities, occludedBlockEntities, visibleSections;
    private static final ClassValue<Boolean> VANILLA_CLASS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith("net.minecraft.");
        }
    };
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
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;
        if (level != current) reset(current);
        Vec3 pos = camera.position();
        lastCamPos = pos;
        cameraX = pos.x; cameraY = pos.y; cameraZ = pos.z;
        camCellX = lower(cameraX); camCellY = lower(cameraY); camCellZ = lower(cameraZ);
        entityShadowsEnabled = minecraft.options.entityShadows().get();
        hasGlobalBlockEntities = current != null && !current.getGloballyRenderedBlockEntities().isEmpty();
        renderThread = Thread.currentThread();
        frustum = camera.getCullFrustum();
        frameActive = true; cameraValid = current != null;
        frame++; now = System.nanoTime();
        VisualsRuntime.tickFrame(now);
        occludedEntities = occludedBlockEntities = 0;
        SECTIONS.releaseSections();
    }
    public static void endFrame(int renderedSections) {
        visibleSections = renderedSections;
        frameActive = false; frame++; SECTIONS.releaseSections(); ParticleCullingManager.endFrame();
    }
    private static void releaseSection() { SECTIONS.releaseSections(); }
    public static void terrainChanged() {
        terrainStamp++;
        SECTIONS.clear();
        // Every mutation invalidates the geometry proof, including cached visible results.
        ParticleCullingManager.invalidateLight();
    }
    public static void reset(ClientLevel current) {
        level = current; cameraValid = false; frustum = null; frameActive = false; lastCamPos = null;
        hasGlobalBlockEntities = false;
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
                || !VANILLA_CLASS.get(renderer.getClass())) return false;
        AABB box = RenderPlatform.cullingBox(renderer, entity);
        if (box.maxX - box.minX > 8 || box.maxY - box.minY > 8 || box.maxZ - box.minZ > 8) return false;
        double dx = entity.xOld - entity.getX(), dy = entity.yOld - entity.getY(), dz = entity.zOld - entity.getZ();
        BOUNDS.entity(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                dx, dy, dz, entity instanceof Mob mob ? mob.getScale() : 1);
        if (frameActive ? entityShadowsEnabled : Minecraft.getInstance().options.entityShadows().get()) {
            // Vanilla EntityRenderer/MobRenderer contracts, inspected from 26.2 bytecode. Unknown
            // radius overrides use the engine's absolute 32-block cap instead of an estimate.
            float radius = 32;
            if (STANDARD_SHADOW.get(renderer.getClass())) {
                radius = ((EntityRendererAccessor) renderer).ephemera$shadowRadius();
                if (entity instanceof Mob mob) radius = radius * mob.getScale() * mob.getAgeScale();
                radius = Math.min(32, radius);
            }
            if (!Float.isFinite(radius)) return false;
            BOUNDS.shadow(entity.xOld, entity.yOld, entity.zOld,
                    entity.getX(), entity.getY(), entity.getZ(), Math.max(0, radius));
        }
        boolean hidden = cached(ENTITIES, entity.getId(), BOUNDS.x0, BOUNDS.y0, BOUNDS.z0,
                BOUNDS.x1, BOUNDS.y1, BOUNDS.z1);
        if (hidden) occludedEntities++;
        return hidden;
    }
    public static boolean cullBlockEntity(BlockEntity entity, boolean global, BlockEntityRenderer<?, ?> renderer) {
        if (!EphemeraCommon.config().blockEntityOcclusionCulling || global || !ready()
                || entity.getLevel() != level || entity instanceof BeaconBlockEntity
                || (hasGlobalBlockEntities && level.getGloballyRenderedBlockEntities().contains(entity))
                || renderer == null || renderer.shouldRenderOffScreen()
                || !VANILLA_CLASS.get(renderer.getClass())) return false;
        // Type-specific envelopes cover moving lids, attached chest halves and hanging geometry.
        if (!(entity instanceof ChestBlockEntity || entity instanceof EnderChestBlockEntity
                || entity instanceof SignBlockEntity || entity instanceof ShulkerBoxBlockEntity
                || entity instanceof BannerBlockEntity)
                || !VANILLA_CLASS.get(entity.getClass())) return false;
        BlockPos p = entity.getBlockPos();
        BOUNDS.block(p.getX(), p.getY(), p.getZ());
        BlockState state = entity.getBlockState();
        if (entity instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction neighbor = ChestBlock.getConnectedDirection(state);
            BOUNDS.x0 += Math.min(0, neighbor.getStepX()); BOUNDS.x1 += Math.max(0, neighbor.getStepX());
            BOUNDS.z0 += Math.min(0, neighbor.getStepZ()); BOUNDS.z1 += Math.max(0, neighbor.getStepZ());
        } else if (entity instanceof ShulkerBoxBlockEntity && state.hasProperty(ShulkerBoxBlock.FACING)) {
            // A shulker can open sideways or downward; include the complete swept lid.
            Direction facing = state.getValue(ShulkerBoxBlock.FACING);
            BOUNDS.x0 += Math.min(0, facing.getStepX()) * .5; BOUNDS.x1 += Math.max(0, facing.getStepX()) * .5;
            BOUNDS.y0 += Math.min(0, facing.getStepY()) * .5; BOUNDS.y1 += Math.max(0, facing.getStepY()) * .5;
            BOUNDS.z0 += Math.min(0, facing.getStepZ()) * .5; BOUNDS.z1 += Math.max(0, facing.getStepZ()) * .5;
            // The square lid also rotates about its facing axis while opening.
            BOUNDS.x0 -= .15; BOUNDS.z0 -= .15; BOUNDS.x1 += .15; BOUNDS.z1 += .15;
            if (facing.getStepY() == 0) { BOUNDS.y0 -= .25; BOUNDS.y1 += .25; }
        } else if (entity instanceof BannerBlockEntity) {
            BOUNDS.y1 = p.getY() + 2;
            if (state.getBlock() instanceof WallBannerBlock) BOUNDS.y0 = p.getY() - 1;
            BOUNDS.x0 -= .25; BOUNDS.z0 -= .25; BOUNDS.x1 += .25; BOUNDS.z1 += .25;
        } else if (entity instanceof HangingSignBlockEntity) {
            BOUNDS.y0 = p.getY() - 1;
            BOUNDS.x0 -= .5; BOUNDS.z0 -= .5; BOUNDS.x1 += .5; BOUNDS.z1 += .5;
        } else if (entity instanceof SignBlockEntity) {
            // Diagonal standing signs and their text can cross a block-cell corner.
            BOUNDS.x0 -= .25; BOUNDS.z0 -= .25; BOUNDS.x1 += .25; BOUNDS.z1 += .25;
        }
        boolean hidden = cached(BLOCK_ENTITIES, p.asLong(), BOUNDS.x0, BOUNDS.y0, BOUNDS.z0,
                BOUNDS.x1, BOUNDS.y1, BOUNDS.z1);
        if (hidden) occludedBlockEntities++;
        return hidden;
    }
    private static boolean ready() {
        if (!cameraValid || level == null) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (level != minecraft.level) return false;
        if (frameActive && Thread.currentThread() == renderThread) {
            Vec3 current = minecraft.gameRenderer.mainCamera().position();
            return current == lastCamPos || (cameraX == current.x && cameraY == current.y && cameraZ == current.z);
        }
        if (!minecraft.isSameThread()) return false;
        Vec3 current = minecraft.gameRenderer.mainCamera().position();
        return current == lastCamPos || (cameraX == current.x && cameraY == current.y && cameraZ == current.z);
    }
    private static int lower(double v) { return (int) Math.floor(v * 4); }
    private static int upper(double v) { return (int) Math.ceil(v * 4); }
    private static boolean cached(DirectMappedSweepCache cache, long id,
                                  double x0, double y0, double z0, double x1, double y1, double z1) {
        if (!finite(x0, y0, z0, x1, y1, z1)) return false;
        int cx = camCellX, cy = camCellY, cz = camCellZ;
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
        // Equal quantized bounds share the proof across weather and API consumers.
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
        } finally { if (!frameActive) releaseSection(); }
    }
    public static boolean isOpaque(int x, int y, int z) {
        if (!ready()) return false;
        try { return isOpaqueUnchecked(x, y, z); }
        finally { if (!frameActive) releaseSection(); }
    }
    private static boolean isOpaqueUnchecked(int x, int y, int z) {
        return SECTIONS.isOpaque(level, x, y, z, terrainStamp, frame);
    }
    public static boolean particleOccluded(double x, double y, double z, double radius) {
        if (!ready()) return false;
        try { return PARTICLES.isOccluded(cameraX, cameraY, cameraZ, x, y, z, radius, frame, terrainStamp); }
        finally { if (!frameActive) releaseSection(); }
    }
    /** Checked once at particle-extraction start so per-particle tests skip Minecraft/camera/thread checks. */
    public static boolean isReadyForParticles(Camera camera) {
        if (!frameActive || !ready() || camera == null) return false;
        var pos = camera.position();
        if (cameraX != pos.x || cameraY != pos.y || cameraZ != pos.z) return false;
        return PARTICLES.prepareFrame(cameraX, cameraY, cameraZ, frame, terrainStamp);
    }
    /** Fast unchecked particle occlusion probe; caller must have verified {@link #isReadyForParticles} this frame. */
    public static boolean particleOccludedFast(double x, double y, double z, double radius) {
        return PARTICLES.isOccludedFast(x, y, z, radius);
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
