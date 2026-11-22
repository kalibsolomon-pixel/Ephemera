package com.ephemera.render.terrain;

import com.ephemera.core.terrain.BitmaskFaceCuller;
import com.ephemera.mixin.client.terrain.TerrainFluidStateModelSetAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

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
	private static final ClassValue<Boolean> VANILLA_MODEL = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return type.getName().startsWith("net.minecraft.");
		}
	};

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
		 * Called for every block whose render shape is {@code MODEL}. Returns true when the
		 * block's tesselation can be skipped because it would produce no quads.
		 */
		public boolean onModelBlock(BlockState state, BlockPos pos) {
			// Cheap cached flag and bitmask test first; the model lookup is a hash map get.
			boolean buried = skipBuried && state.isSolidRender() && isBuried(pos);
			if (!buried && !trackSprites) {
				return false;
			}
			BlockStateModel model = blockModels.get(state);
			boolean vanilla = VANILLA_MODEL.get(model.getClass());
			if (buried && vanilla && hasOnlyCulledQuads(model, state, pos)) {
				return true;
			}
			if (trackSprites) {
				if (!vanilla) {
					untracked = true;
				} else if (model.hasMaterialFlag(BakedQuad.FLAG_ANIMATED)) {
					collectAnimatedSprites(model, state, pos);
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
			for (int y = -1; y <= 16; y++) {
				for (int z = -1; z <= 16; z++) {
					for (int x = -1; x <= 16; x++) {
						BlockState s = region.getBlockState(p.set(originX + x, originY + y, originZ + z));
						if (s.isAir()) {
							continue;
						}
						int mask = 0;
						for (Direction d : DIRECTIONS) {
							if (s.getFaceOcclusionShape(d) == block) {
								mask |= 1 << d.ordinal();
							}
						}
						if (mask != 0) {
							culler.set(x, y, z, mask);
						}
					}
				}
			}
		}

		private boolean hasOnlyCulledQuads(BlockStateModel model, BlockState state, BlockPos pos) {
			List<BlockStateModelPart> list = parts;
			random.setSeed(state.getSeed(pos));
			model.collectParts(random, list);
			try {
				for (int i = 0, n = list.size(); i < n; i++) {
					if (!list.get(i).getQuads(null).isEmpty()) {
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
