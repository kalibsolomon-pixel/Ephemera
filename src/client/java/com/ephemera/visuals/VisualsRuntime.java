package com.ephemera.visuals;

import com.ephemera.EphemeraCommon;
import com.ephemera.api.EphemeraApi;
import com.ephemera.api.EphemeraShaderHooks;
import com.ephemera.core.config.EphemeraConfig;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Single gate for every visual feature: config flag AND no external shader pack AND no
 * competing mod already providing the same feature. Gate values are recomputed at most every
 * 100 ms (or on {@link #invalidate()}) so per-entity and per-quad callers pay one volatile read.
 */
public final class VisualsRuntime {
	private static final String[] DYNAMIC_LIGHT_MODS = {"lambdynlights", "dynamiclights", "ryoamiclights", "sodiumdynamiclights"};
	private static final boolean DYNAMIC_LIGHT_COMPETITOR = anyLoaded(DYNAMIC_LIGHT_MODS);
	private static final boolean AURORA_WORLD_SHADERS = FabricLoader.getInstance().isModLoaded("aurora")
		&& VisualsRuntime.class.getResource("/com/aurora/client/mixin/GradientDitherSourceMixin.class") != null;
	private static final long GATE_TTL_NS = 100_000_000L;

	/** How much farther underwater fog reaches when underwater clarity is on. */
	public static final float UNDERWATER_CLARITY_SCALE = 1.45f;

	private record Gates(
		boolean smoothFog,
		boolean gradientDithering,
		boolean atmosphericHorizon,
		boolean underwaterClarity,
		boolean dynamicLights,
		boolean smoothChunkFade,
		boolean enhancedAmbientOcclusion,
		int anisotropy
	) {}

	private static volatile Gates gates;
	private static volatile long gatesAtNs;

	private VisualsRuntime() {}

	/** Safe to call from any mixin or screen. Installs the Iris hooks on first use if nobody has yet. */
	public static void init() {
		IrisCompatService.init();
	}

	/** Forces the next gate read to re-evaluate config and shader-pack state. */
	public static void invalidate() {
		gatesAtNs = 0L;
		gates = null;
	}

	/** Refreshes cached gates at most every 100 ms; called once per frame on the render thread. */
	public static void tickFrame(long nowNs) {
		if (gates == null || nowNs - gatesAtNs > GATE_TTL_NS) {
			gates = compute();
			gatesAtNs = nowNs;
		}
	}

	public static EphemeraConfig cfg() {
		return EphemeraCommon.config();
	}

	public static EphemeraShaderHooks hooks() {
		return EphemeraApi.get().map(EphemeraApi.Service::shaders).orElse(IrisCompatService.INSTANCE);
	}

	public static boolean externalShadersActive() {
		return hooks().isExternalShaderPackActive();
	}

	public static boolean smoothFog() {
		return gates().smoothFog();
	}

	public static boolean gradientDithering() {
		return gates().gradientDithering();
	}

	public static boolean atmosphericHorizon() {
		return gates().atmosphericHorizon();
	}

	public static boolean underwaterClarity() {
		return gates().underwaterClarity();
	}

	public static boolean dynamicLights() {
		return gates().dynamicLights();
	}

	public static boolean smoothChunkFade() {
		return gates().smoothChunkFade();
	}

	public static boolean enhancedAmbientOcclusion() {
		return gates().enhancedAmbientOcclusion();
	}

	/** Effective anisotropy for the block atlas: 0 means leave the vanilla sampler alone. */
	public static int anisotropy() {
		return gates().anisotropy();
	}

	private static Gates gates() {
		Gates current = gates;
		if (current != null) {
			return current;
		}
		long now = System.nanoTime();
		current = compute();
		gates = current;
		gatesAtNs = now;
		return current;
	}

	private static Gates compute() {
		EphemeraConfig c = cfg();
		EphemeraShaderHooks hooks = hooks();
		boolean external = hooks.isExternalShaderPackActive();
		return new Gates(
			!AURORA_WORLD_SHADERS && hooks.ownsSmoothFog(),
			!AURORA_WORLD_SHADERS && hooks.ownsGradientDithering(),
			c.visualAtmosphericHorizon && !external,
			c.visualUnderwaterClarity && !external,
			!DYNAMIC_LIGHT_COMPETITOR && hooks.ownsDynamicLighting(),
			c.visualSmoothChunkFade && !external,
			c.visualEnhancedAmbientOcclusion && !external,
			c.visualCrispMipmaps && !external ? c.visualAnisotropicLevel : 0
		);
	}

	private static boolean anyLoaded(String[] ids) {
		FabricLoader loader = FabricLoader.getInstance();
		for (String id : ids) {
			if (loader.isModLoaded(id)) {
				return true;
			}
		}
		return false;
	}
}
