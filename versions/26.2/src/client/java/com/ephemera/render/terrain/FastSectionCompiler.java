package com.ephemera.render.terrain;

import com.ephemera.core.terrain.BitmaskFaceCuller;
import com.ephemera.mixin.client.terrain.SingleVariantAccessor;
import com.ephemera.mixin.client.terrain.TerrainFluidStateModelSetAccessor;
import com.ephemera.mixin.client.terrain.WeightedVariantsAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Per-section companion to vanilla {@code SectionCompiler.compile}. It does not replace the
 * compile loop (Fabric's renderer API redirects the block tesselation call inside it), it
 * only answers one question per model block: can tesselation be skipped entirely?
 *
 * <p>A block is skipped when it is a solid-render block, its six neighbours all present a
 * full occlusion face toward it (exactly the test {@code Block.shouldRenderFace} uses), and
 * its vanilla model has no unculled quads for this position's random seed. Such a block
 * would emit zero quads, so skipping it avoids model part collection, six neighbour lookups,
 * AO and tint setup for every interior block of solid terrain.
 *
 * <p>The same pass records the animated sprites referenced by the section's block models for
 * {@link VisibleSpriteTracker}.
 *
 * <p>One {@link Pass} is reused per compile thread; nothing is allocated per block.
 */
public final class FastSectionCompiler {
	private static final Direction[] DIRECTIONS = Direction.values();
	private static final ThreadLocal<Pass> PASSES = ThreadLocal.withInitial(Pass::new);
	// Buried-skip model classification. The memo can only skip tessellation when the model's
	// unculled quad output is provably empty under EVERY random seed, so the classification
	// quantifies over all variants instead of sampling one (stone/deepslate/dirt-class states are
	// WeightedVariants whose pick varies per position). Exact class checks (never instanceof)
	// keep modded subclasses and Fabric Renderer API models (MeshQuadCollection returns empty
	// getQuads(null) but emits via emitQuads) permanently out of the fast path.
	private static final int MAX_MODEL_DEPTH = 8;
	private static final byte CLASS_SKIP = 1;              // every seed provably emits zero unculled quads
	private static final byte CLASS_VANILLA_NON_EMPTY = 2; // every seed provably emits >= 1 unculled quad
	private static final byte CLASS_EVALUATE = 3;          // vanilla but seed-dependent: per-position check
	private static final byte CLASS_UNSAFE = 4;            // modded/subclass/mesh-backed: never skip

	private static boolean isOnlyCulledVanillaPart(@Nullable BlockStateModelPart part) {
		if (part == null || part.getClass() != SimpleModelWrapper.class) {
			return false;
		}
		QuadCollection quads = ((SimpleModelWrapper) part).quads();
		return quads != null
			&& quads.getClass() == QuadCollection.class
			&& quads.getQuads(null).isEmpty();
	}

	private static byte classify(@Nullable BlockStateModel model, int depth) {
		if (model == null || depth > MAX_MODEL_DEPTH) {
			return CLASS_UNSAFE;
		}
		Class<?> cls = model.getClass();
		if (cls == SingleVariant.class) {
			if (!((Object) model instanceof SingleVariantAccessor)) {
				return CLASS_EVALUATE; // accessor did not apply: fail open to the per-position check
			}
			BlockStateModelPart part = ((SingleVariantAccessor) (Object) model).ephemera$getModel();
			if (part == null || part.getClass() != SimpleModelWrapper.class) {
				return CLASS_UNSAFE;
			}
			QuadCollection quads = ((SimpleModelWrapper) part).quads();
			if (quads == null || quads.getClass() != QuadCollection.class) {
				return CLASS_UNSAFE;
			}
			return quads.getQuads(null).isEmpty() ? CLASS_SKIP : CLASS_VANILLA_NON_EMPTY;
		}
		if (cls == WeightedVariants.class) {
			if (!((Object) model instanceof WeightedVariantsAccessor)) {
				return CLASS_EVALUATE;
			}
			WeightedList<BlockStateModel> list = ((WeightedVariantsAccessor) (Object) model).ephemera$getList();
			// isEmpty() also rejects all-zero-weight lists (their getRandomOrThrow throws).
			if (list == null || list.isEmpty()) {
				return CLASS_UNSAFE;
			}
			List<Weighted<BlockStateModel>> entries = list.unwrap();
			if (entries.isEmpty()) {
				return CLASS_UNSAFE;
			}
			boolean sawSkip = false;
			boolean sawNonEmpty = false;
			boolean sawEvaluate = false;
			for (int i = 0, n = entries.size(); i < n; i++) {
				Weighted<BlockStateModel> entry = entries.get(i);
				if (entry == null) {
					return CLASS_UNSAFE;
				}
				byte child = classify(entry.value(), depth + 1);
				if (child == CLASS_UNSAFE) {
					return CLASS_UNSAFE; // poison: a modded child may emit via emitQuads despite empty collectParts
				}
				if (child == CLASS_SKIP) {
					sawSkip = true;
				} else if (child == CLASS_VANILLA_NON_EMPTY) {
					sawNonEmpty = true;
				} else {
					sawEvaluate = true;
				}
			}
			if (sawEvaluate || (sawSkip && sawNonEmpty)) {
				return CLASS_EVALUATE;
			}
			return sawSkip ? CLASS_SKIP : CLASS_VANILLA_NON_EMPTY;
		}
		if (cls == MultiPartModel.class) {
			// Only 4 vanilla solid-render multipart blocks exist and the lazily-cached selection
			// list is not safely inspectable without deeper mixins; the per-position check handles them.
			return CLASS_EVALUATE;
		}
		return CLASS_UNSAFE;
	}

	private static volatile boolean fluidHandlersChecked;
	private static volatile boolean waterHandlerDefault = true;
	private static volatile boolean lavaHandlerDefault = true;

	private static boolean isDefaultFluidHandler(Fluid fluid) {
		if (!fluidHandlersChecked) {
			checkDefaultFluidHandlers();
		}
		if (fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER) {
			return waterHandlerDefault;
		}
		if (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) {
			return lavaHandlerDefault;
		}
		return false;
	}

	private static void checkDefaultFluidHandlers() {
		boolean waterOk = true;
		boolean lavaOk = true;
		try {
			Class<?> regClass = Class.forName("net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry");
			Object instance = regClass.getField("INSTANCE").get(null);
			Method get = regClass.getMethod("get", Fluid.class);
			waterOk = isBuiltinFluidHandler(get.invoke(instance, Fluids.WATER))
				&& isBuiltinFluidHandler(get.invoke(instance, Fluids.FLOWING_WATER));
			lavaOk = isBuiltinFluidHandler(get.invoke(instance, Fluids.LAVA))
				&& isBuiltinFluidHandler(get.invoke(instance, Fluids.FLOWING_LAVA));
		} catch (ClassNotFoundException ignored) {
			// Fabric fluid rendering API not installed -> vanilla FluidRenderer only.
		} catch (Throwable t) {
			waterOk = false;
			lavaOk = false;
		}
		waterHandlerDefault = waterOk;
		lavaHandlerDefault = lavaOk;
		fluidHandlersChecked = true;
	}

	private static boolean isBuiltinFluidHandler(@Nullable Object handler) {
		if (handler == null) {
			return true;
		}
		String name = handler.getClass().getName();
		return name.startsWith("net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderHandlerRegistryImpl$")
			|| name.startsWith("net.minecraft.");
	}

	private FastSectionCompiler() {}

	/** Starts a section compile; returns null when no feature needs per-block work. */
	public static @Nullable Pass begin(SectionPos sectionPos, RenderSectionRegion region,
									   BlockStateModelSet blockModels, FluidStateModelSet fluidModels) {
		try {
			boolean skip = TerrainFeatures.bitmaskMeshing();
			boolean sprites = TerrainFeatures.animateOnlyVisible();
			if (!skip && !sprites) {
				return null;
			}
			Pass pass = PASSES.get();
			pass.reset(sectionPos, region, blockModels, skip, sprites);
			if (sprites) {
				scanFluidSprites(pass.generation, fluidModels);
			}
			return pass;
		} catch (Throwable t) {
			TerrainFeatures.disableCompiler(t);
			return null;
		}
	}

	/** Marks every fluid model sprite as never skippable, once per atlas generation. */
	private static void scanFluidSprites(VisibleSpriteTracker.Generation g, FluidStateModelSet fluidModels) {
		if (g.fluidsScanned || g.count == 0) {
			return;
		}
		if ((Object) fluidModels instanceof TerrainFluidStateModelSetAccessor access) {
			for (FluidModel model : access.getEphemeraModelByFluid().values()) {
				markNeverSkip(g, model.stillMaterial());
				markNeverSkip(g, model.flowingMaterial());
				markNeverSkip(g, model.overlayMaterial());
			}
			g.fluidsScanned = true;
		} else {
			// Without the accessor fluid sprites are unknown; make every sprite fail safe.
			Arrays.fill(g.neverSkip, true);
			g.fluidsScanned = true;
		}
	}

	private static void markNeverSkip(VisibleSpriteTracker.Generation g, Material.@Nullable Baked material) {
		if (material != null) {
			int id = VisibleSpriteTracker.idOf(material.sprite());
			if (id >= 0 && id < g.count) {
				g.neverSkip[id] = true;
			}
		}
	}

	public static final class Pass {
		private final BitmaskFaceCuller culler = new BitmaskFaceCuller();
		private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
		private final RandomSource random = RandomSource.createThreadLocalInstance(0L);
		private final List<BlockStateModelPart> parts = new ObjectArrayList<>();
		private RenderSectionRegion region;
		private BlockStateModelSet blockModels;
		private int originX;
		private int originY;
		private int originZ;
		private boolean skipBuried;
		private boolean trackSprites;
		private boolean maskBuilt;
		private boolean untracked;
		private VisibleSpriteTracker.Generation generation;
		private int[] spriteIds = new int[16];
		private int spriteCount;
		private int[] spriteStamp = new int[0];
		private int stamp;
		private final BlockState[] maskStates = new BlockState[64];
		private final byte[] maskBits = new byte[64];
		// Per-section buried-skip memo (cleared in reset and finish): a solid section holds
		// ~2,000-3,800 buried blocks across only ~5-15 distinct states, so this removes nearly all
		// per-block model lookups, ClassValue probes, and collectParts scans. Keyed by state
		// identity within one compile where the BlockStateModelSet is fixed, so resource reloads
		// can never stale it. 1-slot MRU + direct-mapped table matching the maskStates pattern.
		private static final byte MEMO_UNKNOWN = 0;
		private static final byte MEMO_SKIP = 1;
		private static final byte MEMO_VANILLA = 2;
		private static final byte MEMO_EVALUATE = 3;
		private static final byte MEMO_MODDED = 4;
		private BlockState lastMemoState;
		private byte lastMemoDecision;
		private final BlockState[] memoStates = new BlockState[64];
		private final byte[] memoDecisions = new byte[64];

		private void clearMemo() {
			lastMemoState = null;
			lastMemoDecision = MEMO_UNKNOWN;
			Arrays.fill(memoStates, null);
			Arrays.fill(memoDecisions, MEMO_UNKNOWN);
		}

		private byte memoDecision(BlockState state) {
			if (state == lastMemoState) {
				return lastMemoDecision;
			}
			int slot = (System.identityHashCode(state) * 0x9E3779B9) >>> 26;
			byte decision;
			if (memoStates[slot] == state) {
				decision = memoDecisions[slot];
			} else {
				byte cls = classify(blockModels.get(state), 0);
				decision = cls == CLASS_SKIP ? MEMO_SKIP
					: cls == CLASS_VANILLA_NON_EMPTY ? MEMO_VANILLA
					: cls == CLASS_EVALUATE ? MEMO_EVALUATE
					: MEMO_MODDED;
				memoStates[slot] = state;
				memoDecisions[slot] = decision;
			}
			lastMemoState = state;
			lastMemoDecision = decision;
			return decision;
		}

		void reset(SectionPos sectionPos, RenderSectionRegion region, BlockStateModelSet blockModels, boolean skip, boolean sprites) {
			this.region = region;
			this.blockModels = blockModels;
			this.originX = sectionPos.minBlockX();
			this.originY = sectionPos.minBlockY();
			this.originZ = sectionPos.minBlockZ();
			this.skipBuried = skip;
			this.trackSprites = sprites;
			this.maskBuilt = false;
			this.untracked = false;
			clearMemo();
			this.generation = VisibleSpriteTracker.generation();
			this.spriteCount = 0;
			if (spriteStamp.length < generation.count) {
				spriteStamp = new int[generation.count];
				stamp = 0;
			}
			if (++stamp == Integer.MAX_VALUE) {
				Arrays.fill(spriteStamp, 0);
				stamp = 1;
			}
		}

		/**
		 * Returns true when a vanilla water/lava block is completely surrounded on all 6 cardinal
		 * faces by the same fluid type, meaning both vanilla {@code FluidRenderer.tesselate} and
		 * Fabric's default fluid handler emit zero quads. Skipping the call avoids 6 neighbor
		 * {@code BlockPos} allocations plus Fabric's per-block {@code ScopedValue}/lambda churn
		 * for ocean, river, and aquifer interiors (~400-560 MB during chunk compilation).
		 */
		public boolean shouldSkipBuriedFluid(FluidState fluidState, BlockPos pos) {
			if (!skipBuried) {
				return false;
			}
			Fluid type = fluidState.getType();
			if (!isDefaultFluidHandler(type)) {
				return false;
			}
			int x = pos.getX();
			int y = pos.getY();
			int z = pos.getZ();
			BlockPos.MutableBlockPos p = scratchPos;
			RenderSectionRegion r = region;
			// Check UP first: immediately rejects open ocean/river surface blocks in 1 lookup.
			if (!r.getBlockState(p.set(x, y + 1, z)).getFluidState().getType().isSame(type)) {
				return false;
			}
			if (!r.getBlockState(p.set(x, y - 1, z)).getFluidState().getType().isSame(type)) {
				return false;
			}
			if (!r.getBlockState(p.set(x, y, z - 1)).getFluidState().getType().isSame(type)) {
				return false;
			}
			if (!r.getBlockState(p.set(x, y, z + 1)).getFluidState().getType().isSame(type)) {
				return false;
			}
			if (!r.getBlockState(p.set(x - 1, y, z)).getFluidState().getType().isSame(type)) {
				return false;
			}
			return r.getBlockState(p.set(x + 1, y, z)).getFluidState().getType().isSame(type);
		}

		/**
		 * Called for every block whose render shape is {@code MODEL}. Returns true when the
		 * block's tesselation can be skipped because it would produce no quads.
		 */
		public boolean onModelBlock(BlockState state, BlockPos pos) {
			// Cheap cached flag and bitmask test first; the model lookup is a hash map get.
			boolean buried = skipBuried && state.isSolidRender() && isBuried(pos);
			if (!buried && !trackSprites) {
				return false;
			}
			byte decision = memoDecision(state);
			BlockStateModel model = null;
			if (buried) {
				if (decision == MEMO_SKIP) {
					// Every seed of this baked model graph emits zero unculled quads, so the
					// fully-buried block tessellates to nothing without any per-position work.
					return true;
				}
				if (decision == MEMO_EVALUATE) {
					model = blockModels.get(state);
					if (hasOnlyCulledQuads(model, state, pos)) {
						return true;
					}
				}
				// MEMO_VANILLA provably has unculled quads and MEMO_MODDED may emit via Fabric's
				// emitQuads path; both must tessellate normally.
			}
			if (trackSprites) {
				if (decision == MEMO_MODDED) {
					untracked = true;
				} else {
					// Vanilla model graphs (including MEMO_SKIP: exposed directional faces can
					// still be animated when the block is not fully buried).
					if (model == null) {
						model = blockModels.get(state);
					}
					if (model.hasMaterialFlag(BakedQuad.FLAG_ANIMATED)) {
						collectAnimatedSprites(model, state, pos);
					}
				}
			}
			return false;
		}

		private boolean isBuried(BlockPos pos) {
			if (!maskBuilt) {
				buildMask();
			}
			return culler.isBuried(pos.getX() - originX, pos.getY() - originY, pos.getZ() - originZ);
		}

		private void buildMask() {
			maskBuilt = true;
			culler.clear();
			VoxelShape block = Shapes.block();
			BlockPos.MutableBlockPos p = scratchPos;
			BlockState[] states = maskStates;
			byte[] bits = maskBits;
			Arrays.fill(states, null);
			BlockState lastState = null;
			int lastMask = 0;
			for (int y = -1; y <= 16; y++) {
				for (int z = -1; z <= 16; z++) {
					for (int x = -1; x <= 16; x++) {
						BlockState s = region.getBlockState(p.set(originX + x, originY + y, originZ + z));
						if (s.isAir()) {
							continue;
						}
						int mask;
						if (s == lastState) {
							mask = lastMask;
						} else {
							int slot = (System.identityHashCode(s) * 0x9E3779B9) >>> 26;
							if (states[slot] == s) {
								mask = bits[slot] & 0x3F;
							} else {
								mask = 0;
								for (Direction d : DIRECTIONS) {
									if (s.getFaceOcclusionShape(d) == block) {
										mask |= 1 << d.ordinal();
									}
								}
								states[slot] = s;
								bits[slot] = (byte) mask;
							}
							lastState = s;
							lastMask = mask;
						}
						if (mask != 0) {
							culler.set(x, y, z, mask);
						}
					}
				}
			}
			Arrays.fill(states, null);
		}

		private boolean hasOnlyCulledQuads(BlockStateModel model, BlockState state, BlockPos pos) {
			List<BlockStateModelPart> list = parts;
			random.setSeed(state.getSeed(pos));
			model.collectParts(random, list);
			try {
				// Empty part lists must NOT count as skippable: a Fabric model can emit unculled
				// quads via emitQuads while collectParts returns nothing.
				if (list.isEmpty()) {
					return false;
				}
				for (int i = 0, n = list.size(); i < n; i++) {
					// Reject Fabric MeshQuadCollection-backed parts: they report empty getQuads(null)
					// but emit real geometry through the Renderer API mesh path.
					if (!isOnlyCulledVanillaPart(list.get(i))) {
						return false;
					}
				}
				return true;
			} finally {
				list.clear();
			}
		}

		private void collectAnimatedSprites(BlockStateModel model, BlockState state, BlockPos pos) {
			List<BlockStateModelPart> list = parts;
			random.setSeed(state.getSeed(pos));
			model.collectParts(random, list);
			try {
				for (int i = 0, n = list.size(); i < n; i++) {
					BlockStateModelPart part = list.get(i);
					collectFrom(part.getQuads(null));
					for (Direction d : DIRECTIONS) {
						collectFrom(part.getQuads(d));
					}
				}
			} finally {
				list.clear();
			}
		}

		private void collectFrom(List<BakedQuad> quads) {
			for (int i = 0, n = quads.size(); i < n; i++) {
				int id = VisibleSpriteTracker.idOf(quads.get(i).materialInfo().sprite());
				if (id < 0 || id >= spriteStamp.length || spriteStamp[id] == stamp) {
					continue;
				}
				spriteStamp[id] = stamp;
				if (spriteCount == spriteIds.length) {
					spriteIds = Arrays.copyOf(spriteIds, spriteCount * 2);
				}
				spriteIds[spriteCount++] = id;
			}
		}

		/** Publishes the recorded sprites onto the compile results. */
		public void finish(SectionCompiler.@Nullable Results results) {
			region = null;
			blockModels = null;
			// Release memo state even when reset() is skipped (abnormal compile exits).
			clearMemo();
			VisibleSpriteTracker.Generation g = generation;
			generation = null;
			if (!trackSprites || g == null) {
				return;
			}
			int[] ids = spriteCount == 0 ? EMPTY : Arrays.copyOf(spriteIds, spriteCount);
			for (int id : ids) {
				if (id < g.count) {
					g.seenInTerrain[id] = true;
				}
			}
			if ((Object) results instanceof CompileResultsSprites holder) {
				holder.ephemera$setSprites(ids, g.id, untracked);
			}
		}
	}

	static final int[] EMPTY = new int[0];
}
