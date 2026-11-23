package com.ephemera.visuals;

import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.visuals.HeldWorldLightMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Single interpolated held-item light source for Ephemera's optional terrain shader illumination.
 *
 * <p>Tracks only the local player's brighter held light source (main hand or offhand), smoothly
 * interpolates its world position with {@code partialTick}, clamps the hand offset against solid
 * neighbor blocks to limit wall leaking, and eases intensity across hotbar switches. Always outputs
 * zeros immediately when disabled or standing down for shaders / competing mods.
 *
 * <p>Render-thread only.
 */
public final class HeldWorldLightTracker {
	private static final BlockPos.MutableBlockPos PROBE_POS = new BlockPos.MutableBlockPos();

	private static long lastSampleNs;
	private static float smoothedLevel;
	private static float relX;
	private static float relY;
	private static float relZ;
	private static float invRadius;
	private static float blockLight240;

	private HeldWorldLightTracker() {}

	/** Updates the cached camera-relative held light for the active frame. */
	public static void sampleForFrame(Vec3 cameraPos, float partialTick) {
		if (cameraPos == null || !VisualsRuntime.heldWorldLight()) {
			zeroOutput();
			smoothedLevel = 0.0f;
			lastSampleNs = 0L;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || player.isSpectator()) {
			zeroOutput();
			smoothedLevel = 0.0f;
			lastSampleNs = 0L;
			return;
		}

		boolean underwater = player.isUnderWater();
		ItemStack main = player.getMainHandItem();
		ItemStack off = player.getOffhandItem();
		int mainEmission = heldEmission(main, underwater);
		int offEmission = heldEmission(off, underwater);
		int targetEmission = Math.max(mainEmission, offEmission);
		boolean fromMainHand = mainEmission >= offEmission;

		long now = System.nanoTime();
		float dtSec = lastSampleNs == 0L ? 1.0f : Math.max(0.0f, (now - lastSampleNs) / 1_000_000_000.0f);
		lastSampleNs = now;
		smoothedLevel = HeldWorldLightMath.smoothLevel(smoothedLevel, targetEmission, dtSec);
		if (smoothedLevel <= 0.001f) {
			zeroOutput();
			return;
		}

		double px = Mth.lerp(partialTick, player.xOld, player.getX());
		double py = Mth.lerp(partialTick, player.yOld, player.getY()) + player.getEyeHeight();
		double pz = Mth.lerp(partialTick, player.zOld, player.getZ());

		// Subtle offset toward the illuminated hand; falls back to player eye if that cell is solid
		// so holding a torch right up against a 1-block wall does not poke the light into the wall.
		float yawRad = player.getViewYRot(partialTick) * Mth.DEG_TO_RAD;
		double fwdX = -Mth.sin(yawRad);
		double fwdZ = Mth.cos(yawRad);
		boolean rightSide = (player.getMainArm() == HumanoidArm.RIGHT) == fromMainHand;
		double sideSign = rightSide ? -1.0 : 1.0;
		double rightX = -fwdZ * sideSign;
		double rightZ = fwdX * sideSign;

		double lx = px + fwdX * 0.28 + rightX * 0.22;
		double ly = py - 0.24;
		double lz = pz + fwdZ * 0.28 + rightZ * 0.22;
		if (isSolidWallBlock(level, lx, ly, lz)) {
			lx = px;
			ly = py - 0.15;
			lz = pz;
		}

		EphemeraConfig cfg = VisualsRuntime.cfg();
		float radius = HeldWorldLightMath.effectiveRadius(smoothedLevel, cfg.visualHeldWorldLightRadius);
		float light240 = HeldWorldLightMath.blockLight240(smoothedLevel, cfg.visualHeldWorldLightStrength);
		if (radius <= 0.001f || light240 <= 0.001f) {
			zeroOutput();
			return;
		}

		relX = (float) (lx - cameraPos.x);
		relY = (float) (ly - cameraPos.y);
		relZ = (float) (lz - cameraPos.z);
		invRadius = 1.0f / radius;
		blockLight240 = light240;
	}

	public static float relX() { return relX; }
	public static float relY() { return relY; }
	public static float relZ() { return relZ; }
	public static float invRadius() { return invRadius; }
	public static float blockLight240() { return blockLight240; }
	public static boolean isEmitting() { return blockLight240 > 0.001f; }

	public static void clear() {
		zeroOutput();
		smoothedLevel = 0.0f;
		lastSampleNs = 0L;
	}

	private static void zeroOutput() {
		relX = 0.0f;
		relY = 0.0f;
		relZ = 0.0f;
		invRadius = 0.0f;
		blockLight240 = 0.0f;
	}

	private static int heldEmission(ItemStack stack, boolean underwater) {
		int emission = DynamicLightTracker.itemLight(stack);
		if (emission <= 0) {
			return 0;
		}
		if (underwater && !isWaterSafeLight(stack.getItem())) {
			return 0;
		}
		return emission;
	}

	private static boolean isWaterSafeLight(Item item) {
		return item == Items.SEA_LANTERN
			|| item == Items.GLOWSTONE
			|| item == Items.SHROOMLIGHT
			|| item == Items.JACK_O_LANTERN
			|| item == Items.CONDUIT
			|| item == Items.OCHRE_FROGLIGHT
			|| item == Items.VERDANT_FROGLIGHT
			|| item == Items.PEARLESCENT_FROGLIGHT
			|| item == Items.MAGMA_BLOCK
			|| item == Items.BEACON
			|| item == Items.END_ROD
			|| item == Items.GLOW_BERRIES
			|| item == Items.GLOW_LICHEN
			|| item == Items.SEA_PICKLE
			|| item == Items.AMETHYST_CLUSTER;
	}

	private static boolean isSolidWallBlock(ClientLevel level, double x, double y, double z) {
		PROBE_POS.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
		BlockState state = level.getBlockState(PROBE_POS);
		return !state.isAir() && state.isSolidRender();
	}
}
