package com.ephemera.mixin;

import com.ephemera.EphemeraCommon;
import com.ephemera.core.config.EphemeraConfig;
import com.ephemera.core.diagnostics.MixinHealth;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-soft, stand-down-aware mixin plugin for common (client+server) optimizations.
 * Supports -Dephemera.mixin.disable=NameA,NameB for controlled ABBA benchmarking.
 */
public class EphemeraCommonMixinPlugin implements IMixinConfigPlugin {
	protected final Set<String> disabledViaProp = parseDisabledProp();
	protected final Map<String, List<String>> yieldRules = new HashMap<>();

	protected static Set<String> parseDisabledProp() {
		String raw = System.getProperty("ephemera.mixin.disable", "");
		if (raw.isBlank()) {
			return Collections.emptySet();
		}
		Set<String> out = new HashSet<>();
		for (String part : raw.split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}

	protected void registerYield(String mixinSimpleName, String... ownerModIds) {
		yieldRules.put(mixinSimpleName, Arrays.asList(ownerModIds));
	}

	protected boolean isConfigEnabled(String simpleName, EphemeraConfig cfg) {
		return switch (simpleName) {
			case "BlockStateCacheMixin", "BlockStateMixin" -> cfg.memoryCompactBlockStates || cfg.memoryDeduplicateShapes;
			case "BlockShapeMixin" -> cfg.memoryDeduplicateShapes;
			case "CompactTrigMixin" -> cfg.engineFastMath;
			case "ChunkModuloMixin", "LevelChunkIndexMixin", "PaletteIndexAccessor", "SectionStateMixin", "FluidAccess", "FluidStateFastMixin" -> cfg.engineFastChunkLookup;
			case "SimpleBitStorageFastMixin", "LinearPaletteFastMixin", "PalettedContainerFastMixin" -> cfg.engineFastPalettedStorage;
			case "ImprovedNoiseFastMixin", "PerlinNoiseFastMixin", "BeardifierFastMixin", "NoiseChunkFastMixin", "NoiseInterpolatorAccess" -> cfg.engineFastWorldgenNoise;
			case "BiomeManagerFastMixin", "ClimateRTreeFastMixin", "ClimateRTreeNodeFastMixin" -> cfg.engineFastBiomeLookup;
			case "UtilWorkerPriorityMixin" -> cfg.engineWorkerPriorityIsolation;
			case "LightChunkCacheMixin", "LightSectionCacheMixin", "LightWorkMixin", "DataLayerMixin" -> cfg.engineFastLighting;
			case "ConnectionFlushMixin" -> cfg.networkFlushConsolidation;
			default -> true;
		};
	}

	@Override
	public void onLoad(String mixinPackage) {
		registerYield("BlockStateCacheMixin", "ferritecore");
		registerYield("BlockStateMixin", "ferritecore");
		registerYield("BlockShapeMixin", "ferritecore", "lithium");
		registerYield("CompactTrigMixin", "lithium");
		registerYield("ChunkModuloMixin", "lithium");
		registerYield("LevelChunkIndexMixin", "lithium");
		registerYield("PaletteIndexAccessor", "lithium");
		registerYield("SectionStateMixin", "lithium");
		registerYield("FluidAccess", "lithium");
		registerYield("FluidStateFastMixin", "lithium");
		registerYield("SimpleBitStorageFastMixin", "lithium", "c2me");
		registerYield("LinearPaletteFastMixin", "lithium", "c2me");
		registerYield("PalettedContainerFastMixin", "lithium", "c2me");
		registerYield("ImprovedNoiseFastMixin", "c2me", "noisium");
		registerYield("PerlinNoiseFastMixin", "c2me", "noisium");
		registerYield("BeardifierFastMixin", "c2me", "lithium");
		registerYield("NoiseChunkFastMixin", "c2me", "noisium");
		registerYield("NoiseInterpolatorAccess", "c2me", "noisium");
		registerYield("BiomeManagerFastMixin", "c2me", "lithium");
		registerYield("ClimateRTreeFastMixin", "c2me", "lithium");
		registerYield("ClimateRTreeNodeFastMixin", "c2me", "lithium");
		registerYield("UtilWorkerPriorityMixin", "c2me");
		registerYield("LightChunkCacheMixin", "scalablelux", "lithium");
		registerYield("LightSectionCacheMixin", "scalablelux", "lithium");
		registerYield("LightWorkMixin", "scalablelux", "lithium");
		registerYield("DataLayerMixin", "scalablelux", "lithium");
		registerYield("ConnectionFlushMixin", "krypton");
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (disabledViaProp.contains(simple) || disabledViaProp.contains("ALL")) {
			MixinHealth.recordStoodDown(simple, "ephemera.mixin.disable");
			return false;
		}
		EphemeraConfig cfg = EphemeraCommon.config();
		if (!isConfigEnabled(simple, cfg)) {
			MixinHealth.recordStoodDown(simple, "config");
			return false;
		}
		if (cfg.modStandDownEnabled) {
			List<String> owners = yieldRules.get(simple);
			if (owners != null) {
				FabricLoader loader = FabricLoader.getInstance();
				for (String modId : owners) {
					if (loader.isModLoaded(modId)) {
						MixinHealth.recordStoodDown(simple, modId);
						return false;
					}
				}
			}
		}
		MixinHealth.recordApplied(simple);
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
