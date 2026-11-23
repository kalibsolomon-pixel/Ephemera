package com.ephemera.mixin.client.terrain;

import com.ephemera.EphemeraCommon;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces {@link VisGraph}'s {@code BitSet} ({@code expandTo} bounds checks), per-component
 * {@code IntArrayFIFOQueue} and {@code EnumSet<Direction>} allocations, and switch-based neighbor
 * lookups with a flat {@code long[64]} bitset, a thread-local {@code short[4096]} BFS queue, and
 * 6-bit face masks. Produces identical {@link VisibilitySet} results.
 */
@Mixin(VisGraph.class)
abstract class VisGraphFastMixin {
	@Unique private static final ThreadLocal<short[]> EPHEMERA$BFS_QUEUE = ThreadLocal.withInitial(() -> new short[4096]);
	@Unique private static final int EPHEMERA$WEST_BIT = 1 << Direction.WEST.ordinal();
	@Unique private static final int EPHEMERA$EAST_BIT = 1 << Direction.EAST.ordinal();
	@Unique private static final int EPHEMERA$NORTH_BIT = 1 << Direction.NORTH.ordinal();
	@Unique private static final int EPHEMERA$SOUTH_BIT = 1 << Direction.SOUTH.ordinal();
	@Unique private static final int EPHEMERA$DOWN_BIT = 1 << Direction.DOWN.ordinal();
	@Unique private static final int EPHEMERA$UP_BIT = 1 << Direction.UP.ordinal();

	@Shadow @Final private static Direction[] DIRECTIONS;
	@Shadow @Final private static int[] INDEX_OF_EDGES;
	@Shadow private int empty;

	@Unique private final long[] ephemera$bits = new long[64];
	@Unique private boolean ephemera$usedFast;

	@Inject(method = "setOpaque", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastSetOpaque(BlockPos pos, CallbackInfo ci) {
		if (!EphemeraCommon.config().terrainBitmaskMeshing) {
			return;
		}
		this.ephemera$usedFast = true;
		int idx = (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 15) << 8);
		this.ephemera$bits[idx >>> 6] |= 1L << idx;
		this.empty--;
		ci.cancel();
	}

	@Inject(method = "resolve", at = @At("HEAD"), cancellable = true)
	private void ephemera$fastResolve(CallbackInfoReturnable<VisibilitySet> cir) {
		if (!EphemeraCommon.config().terrainBitmaskMeshing) {
			return;
		}
		if (!this.ephemera$usedFast && this.empty != 4096) {
			return;
		}
		VisibilitySet result = new VisibilitySet();
		int emp = this.empty;
		if (4096 - emp < 256) {
			result.setAll(true);
			cir.setReturnValue(result);
			return;
		}
		if (emp == 0) {
			result.setAll(false);
			cir.setReturnValue(result);
			return;
		}

		long[] bits = this.ephemera$bits;
		short[] queue = EPHEMERA$BFS_QUEUE.get();
		int[] edges = INDEX_OF_EDGES;
		Direction[] dirs = DIRECTIONS;

		for (int e = 0, edgeLen = edges.length; e < edgeLen; e++) {
			int start = edges[e];
			if ((bits[start >>> 6] & (1L << start)) != 0L) {
				continue;
			}
			bits[start >>> 6] |= 1L << start;
			int head = 0;
			int tail = 1;
			queue[0] = (short) start;
			int faceMask = 0;

			while (head < tail) {
				int idx = queue[head++] & 0xFFF;
				int x = idx & 15;
				int z = (idx >>> 4) & 15;
				int y = idx >>> 8;

				if (x == 0) {
					faceMask |= EPHEMERA$WEST_BIT;
				} else {
					int n = idx - 1;
					int w = n >>> 6;
					long bit = 1L << n;
					if ((bits[w] & bit) == 0L) {
						bits[w] |= bit;
						queue[tail++] = (short) n;
					}
				}
				if (x == 15) {
					faceMask |= EPHEMERA$EAST_BIT;
				} else {
					int n = idx + 1;
					int w = n >>> 6;
					long bit = 1L << n;
					if ((bits[w] & bit) == 0L) {
						bits[w] |= bit;
						queue[tail++] = (short) n;
					}
				}

				if (z == 0) {
					faceMask |= EPHEMERA$NORTH_BIT;
				} else {
					int n = idx - 16;
					int w = n >>> 6;
					long bit = 1L << n;
					if ((bits[w] & bit) == 0L) {
						bits[w] |= bit;
						queue[tail++] = (short) n;
					}
				}
				if (z == 15) {
					faceMask |= EPHEMERA$SOUTH_BIT;
				} else {
					int n = idx + 16;
					int w = n >>> 6;
					long bit = 1L << n;
					if ((bits[w] & bit) == 0L) {
						bits[w] |= bit;
						queue[tail++] = (short) n;
					}
				}

				if (y == 0) {
					faceMask |= EPHEMERA$DOWN_BIT;
				} else {
					int n = idx - 256;
					int w = n >>> 6;
					long bit = 1L << n;
					if ((bits[w] & bit) == 0L) {
						bits[w] |= bit;
						queue[tail++] = (short) n;
					}
				}
				if (y == 15) {
					faceMask |= EPHEMERA$UP_BIT;
				} else {
					int n = idx + 256;
					int w = n >>> 6;
					long bit = 1L << n;
					if ((bits[w] & bit) == 0L) {
						bits[w] |= bit;
						queue[tail++] = (short) n;
					}
				}
			}

			int m1 = faceMask;
			while (m1 != 0) {
				int i1 = Integer.numberOfTrailingZeros(m1);
				m1 &= m1 - 1;
				Direction d1 = dirs[i1];
				result.set(d1, d1, true);
				int m2 = m1;
				while (m2 != 0) {
					int i2 = Integer.numberOfTrailingZeros(m2);
					m2 &= m2 - 1;
					result.set(d1, dirs[i2], true);
				}
			}
		}

		cir.setReturnValue(result);
	}
}
