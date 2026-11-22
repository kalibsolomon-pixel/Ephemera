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
	protected final Map<String, String> requiresRules = new HashMap<>();

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

	protected void registerRequires(String mixinSimpleName, String requiredModId) {
		requiresRules.put(mixinSimpleName, requiredModId);
	}

	protected boolean isConfigEnabled(String simpleName, EphemeraConfig cfg) {
		return switch (simpleName) {
			case "BlockStateCacheMixin", "BlockStateMixin", "StateHolderMixin" -> cfg.memoryCompactBlockStates || cfg.memoryDeduplicateShapes;
			case "BlockShapeMixin", "ArrayVoxelShapeMixin" -> cfg.memoryDeduplicateShapes;
			case "CompactTrigMixin" -> cfg.engineFastMath;
			case "ChunkModuloMixin", "LevelChunkIndexMixin", "SectionStateMixin", "FluidAccess", "FluidStateFastMixin", "ServerChunkCacheFastMixin" -> cfg.engineFastChunkLookup;
			case "CubeVoxelShapeFastMixin", "BlockCollisionsFastMixin" -> cfg.engineFastCollisions;
			case "EntitySectionStorageFastMixin", "TrackedEntityFastMixin" -> cfg.engineFastEntityLookup;
			case "SimpleBitStorageFastMixin", "LinearPaletteFastMixin", "PalettedContainerFastMixin" -> cfg.engineFastPalettedStorage;
			case "ImprovedNoiseFastMixin", "PerlinNoiseFastMixin", "BeardifierFastMixin", "NoiseChunkFastMixin", "NoiseInterpolatorAccess", "SurfaceRulesSequenceFastMixin" -> cfg.engineFastWorldgenNoise;
			case "BiomeManagerFastMixin", "ClimateRTreeFastMixin", "ClimateRTreeNodeFastMixin" -> cfg.engineFastBiomeLookup;
			case "UtilWorkerPriorityMixin" -> cfg.engineWorkerPriorityIsolation;
			case "LightChunkCacheMixin", "LightSectionCacheMixin", "LightWorkMixin", "DataLayerMixin" -> cfg.engineFastLighting;
			case "ConnectionFlushMixin" -> cfg.networkFlushConsolidation;
			case "VarIntFastMixin", "VarLongFastMixin" -> cfg.networkFastVarInt;
			default -> true;
		};
	}

	@Override
	public void onLoad(String mixinPackage) {
		registerYield("BlockStateCacheMixin", "ferritecore");
		registerYield("BlockStateMixin", "ferritecore");
		registerYield("StateHolderMixin", "ferritecore");
		registerYield("ArrayVoxelShapeMixin", "ferritecore");
		registerYield("BlockShapeMixin", "ferritecore", "lithium", "radium", "canary");
		registerYield("CompactTrigMixin", "lithium", "radium", "canary");
		registerYield("ChunkModuloMixin", "lithium", "radium", "canary");
		registerYield("LevelChunkIndexMixin", "lithium", "radium", "canary");
		registerYield("SectionStateMixin", "lithium", "radium", "canary");
		registerYield("FluidAccess", "lithium", "radium", "canary");
		registerYield("FluidStateFastMixin", "lithium", "radium", "canary");
		registerYield("CubeVoxelShapeFastMixin", "lithium", "radium", "canary");
		registerYield("BlockCollisionsFastMixin", "lithium", "radium", "canary");
		registerYield("EntitySectionStorageFastMixin", "lithium", "radium", "canary", "vmp");
		registerYield("TrackedEntityFastMixin", "lithium", "radium", "canary", "vmp");
		registerYield("ServerChunkCacheFastMixin", "lithium", "radium", "canary", "vmp", "c2me");
		registerYield("SurfaceRulesSequenceFastMixin", "c2me", "noisium", "lithium", "radium", "canary", "vmp");
		registerYield("SimpleBitStorageFastMixin", "lithium", "radium", "canary", "c2me");
		registerYield("LinearPaletteFastMixin", "lithium", "radium", "canary", "c2me");
		registerYield("PalettedContainerFastMixin", "lithium", "radium", "canary", "c2me");
		registerYield("ImprovedNoiseFastMixin", "c2me", "noisium");
		registerYield("PerlinNoiseFastMixin", "c2me", "noisium");
		registerYield("BeardifierFastMixin", "c2me", "lithium", "radium", "canary");
		registerYield("NoiseChunkFastMixin", "c2me", "noisium");
		registerYield("NoiseInterpolatorAccess", "c2me", "noisium");
		registerYield("BiomeManagerFastMixin", "c2me", "lithium", "radium", "canary");
		registerYield("ClimateRTreeFastMixin", "c2me", "lithium", "radium", "canary");
		registerYield("ClimateRTreeNodeFastMixin", "c2me", "lithium", "radium", "canary");
		registerYield("UtilWorkerPriorityMixin", "c2me");
		registerYield("LightChunkCacheMixin", "scalablelux", "starlight", "lithium", "radium", "canary");
		registerYield("LightSectionCacheMixin", "scalablelux", "starlight", "lithium", "radium", "canary");
		registerYield("LightWorkMixin", "scalablelux", "starlight", "lithium", "radium", "canary");
		registerYield("DataLayerMixin", "scalablelux", "starlight", "lithium", "radium", "canary");
		registerYield("ConnectionFlushMixin", "krypton");
		registerYield("VarIntFastMixin", "krypton");
		registerYield("VarLongFastMixin", "krypton");
		registerYield("ProfilerFastMixin", "badoptimizations");
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
		String requiredMod = requiresRules.get(simple);
		if (requiredMod != null && !FabricLoader.getInstance().isModLoaded(requiredMod)) {
			MixinHealth.recordStoodDown(simple, "requires:" + requiredMod);
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
