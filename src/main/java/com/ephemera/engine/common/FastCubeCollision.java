package com.ephemera.engine.common;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Zero-allocation full-cube + empty-block AABB collision solver matching vanilla
 * {@link Entity#collideBoundingBox} and {@code Shapes.collide} bit-for-bit.
 *
 * <p>Covers both entity movement and particle physics across air, fluids, and full solid cubes
 * (stone, dirt, grass, glass, leaves, glowstone, obsidian, redstone, etc.) without allocating
 * {@code CollisionContext}, {@code AABB.expandTowards}, {@code BlockCollisions},
 * {@code VoxelShape}, or {@code ImmutableList}. Bails out immediately (returning {@code false})
 * whenever any non-full solid shape, oversized block (fence/wall) below, unloaded chunk, world
 * border proximity, or large swept volume is encountered so vanilla handles every edge case.
 */
public final class FastCubeCollision {
	private static final double EPS = 1.0E-7;

	private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH_POS =
		ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
	private static final ThreadLocal<double[]> SCRATCH_OUT =
		ThreadLocal.withInitial(() -> new double[3]);

	private FastCubeCollision() {}

	public static double[] scratchOut() {
		return SCRATCH_OUT.get();
	}

	/**
	 * Fast check for {@code CollisionGetter.noBlockCollision(entity, box, false)}.
	 *
	 * @return {@code 1} if definitely no block collision, {@code 0} if definitely colliding with a
	 *         full solid block, or {@code -1} to fall back to vanilla {@code BlockCollisions}
	 */
	public static int tryNoBlockCollision(Level level, @Nullable Entity entity, AABB box) {
		if (level.isDebug()) {
			return -1;
		}
		int x0 = Mth.floor(box.minX - EPS);
		int x1 = Mth.floor(box.maxX + EPS);
		int y0 = Mth.floor(box.minY - EPS);
		int y1 = Mth.floor(box.maxY + EPS);
		int z0 = Mth.floor(box.minZ - EPS);
		int z1 = Mth.floor(box.maxZ + EPS);

		int nx = x1 - x0 + 1;
		int ny = y1 - y0 + 1;
		int nz = z1 - z0 + 1;
		if (nx <= 0 || ny <= 0 || nz <= 0 || (long) nx * ny * nz > 64L) {
			return -1;
		}
		int minLevelY = level.getMinY();
		int maxLevelY = level.getMaxY();
		if (y0 < minLevelY || y1 >= maxLevelY) {
			return -1;
		}
		int minSecY = minLevelY >> 4;
		int cx0 = x0 >> 4;
		int cx1 = x1 >> 4;
		int cz0 = z0 >> 4;
		int cz1 = z1 >> 4;
		int scanY0 = y0 > minLevelY ? y0 - 1 : y0;
		int sy0 = (scanY0 >> 4) - minSecY;
		int sy1 = (y1 >> 4) - minSecY;

		FastChunkLookup fastLookup = level instanceof FastChunkLookup f ? f : null;
		boolean lavaIsEmpty = entity == null || entity instanceof ItemEntity;
		BlockPos.MutableBlockPos mpos = SCRATCH_POS.get();

		for (int cz = cz0; cz <= cz1; cz++) {
			int lz0 = (cz == cz0) ? (z0 & 15) : 0;
			int lz1 = (cz == cz1) ? (z1 & 15) : 15;
			int bzBase = cz << 4;
			for (int cx = cx0; cx <= cx1; cx++) {
				LevelChunk lc;
				if (fastLookup != null) {
					lc = fastLookup.ephemera$getLoadedChunk(cx, cz);
				} else {
					ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
					lc = chunk instanceof LevelChunk full ? full : null;
				}
				if (lc == null) {
					return -1;
				}
				LevelChunkSection[] sections = lc.getSections();
				int lx0 = (cx == cx0) ? (x0 & 15) : 0;
				int lx1 = (cx == cx1) ? (x1 & 15) : 15;
				int bxBase = cx << 4;

				for (int sy = sy0; sy <= sy1; sy++) {
					if (sy < 0 || sy >= sections.length) {
						return -1;
					}
					LevelChunkSection sec = sections[sy];
					if (sec == null || sec.hasOnlyAir()) {
						continue;
					}
					int secWorldY = (sy + minSecY) << 4;
					int ly0 = (sy == sy0) ? (scanY0 & 15) : 0;
					int ly1 = (sy == sy1) ? (y1 & 15) : 15;
					for (int ly = ly0; ly <= ly1; ly++) {
						int wy = secWorldY + ly;
						boolean isBelowGuard = wy < y0;
						for (int lz = lz0; lz <= lz1; lz++) {
							int wz = bzBase + lz;
							for (int lx = lx0; lx <= lx1; lx++) {
								BlockState state = sec.getBlockState(lx, ly, lz);
								if (state.isAir()) {
									continue;
								}
								if (isBelowGuard) {
									if (state.hasLargeCollisionShape()) {
										return -1;
									}
									continue;
								}
								Block block = state.getBlock();
								if (block == Blocks.WATER || (lavaIsEmpty && block == Blocks.LAVA)) {
									continue;
								}
								if (state.hasLargeCollisionShape()) {
									return -1;
								}
								int wx = bxBase + lx;
								mpos.set(wx, wy, wz);
								if (state.isCollisionShapeFullBlock(level, mpos)) {
									if (box.maxX - EPS > wx && box.minX + EPS < wx + 1
										&& box.maxY - EPS > wy && box.minY + EPS < wy + 1
										&& box.maxZ - EPS > wz && box.minZ + EPS < wz + 1) {
										return 0;
									}
								} else if (!state.getCollisionShape(level, mpos).isEmpty()) {
									return -1;
								}
							}
						}
					}
				}
			}
		}
		return 1;
	}

	/**
	 * Attempts to resolve block collisions for moving {@code box} by {@code (dx, dy, dz)}.
	 *
	 * @param level world to query
	 * @param entity colliding entity, or {@code null} for particles / context-only queries
	 * @param box starting bounding box
	 * @param dx desired X delta
	 * @param dy desired Y delta
	 * @param dz desired Z delta
	 * @param out length-3 array receiving {@code (resX, resY, resZ)} when returning {@code true}
	 * @return {@code true} if resolved exactly; {@code false} to fall back to vanilla
	 */
	public static boolean tryCollide(
		Level level,
		@Nullable Entity entity,
		AABB box,
		double dx,
		double dy,
		double dz,
		double[] out
	) {
		if (level.isDebug()) {
			return false;
		}
		double minX = dx < 0.0 ? box.minX + dx : box.minX;
		double maxX = dx > 0.0 ? box.maxX + dx : box.maxX;
		double minY = dy < 0.0 ? box.minY + dy : box.minY;
		double maxY = dy > 0.0 ? box.maxY + dy : box.maxY;
		double minZ = dz < 0.0 ? box.minZ + dz : box.minZ;
		double maxZ = dz > 0.0 ? box.maxZ + dz : box.maxZ;

		if (entity != null) {
			WorldBorder border = level.getWorldBorder();
			if (minX <= border.getMinX() + 1.0 || maxX >= border.getMaxX() - 1.0
				|| minZ <= border.getMinZ() + 1.0 || maxZ >= border.getMaxZ() - 1.0) {
				return false;
			}
		}

		int x0 = Mth.floor(minX - EPS);
		int x1 = Mth.floor(maxX + EPS);
		int y0 = Mth.floor(minY - EPS);
		int y1 = Mth.floor(maxY + EPS);
		int z0 = Mth.floor(minZ - EPS);
		int z1 = Mth.floor(maxZ + EPS);

		int nx = x1 - x0 + 1;
		int ny = y1 - y0 + 1;
		int nz = z1 - z0 + 1;
		if (nx <= 0 || ny <= 0 || nz <= 0 || (long) nx * ny * nz > 64L) {
			return false;
		}

		int minLevelY = level.getMinY();
		int maxLevelY = level.getMaxY();
		if (y0 < minLevelY || y1 >= maxLevelY) {
			return false;
		}
		int minSecY = minLevelY >> 4;
		int cx0 = x0 >> 4;
		int cx1 = x1 >> 4;
		int cz0 = z0 >> 4;
		int cz1 = z1 >> 4;
		int scanY0 = y0 > minLevelY ? y0 - 1 : y0;
		int sy0 = (scanY0 >> 4) - minSecY;
		int sy1 = (y1 >> 4) - minSecY;

		FastChunkLookup fastLookup = level instanceof FastChunkLookup f ? f : null;
		boolean lavaIsEmpty = entity == null || entity instanceof ItemEntity;
		BlockPos.MutableBlockPos mpos = SCRATCH_POS.get();
		long solidMask = 0L;

		for (int cz = cz0; cz <= cz1; cz++) {
			int lz0 = (cz == cz0) ? (z0 & 15) : 0;
			int lz1 = (cz == cz1) ? (z1 & 15) : 15;
			int bzBase = cz << 4;
			for (int cx = cx0; cx <= cx1; cx++) {
				LevelChunk lc;
				if (fastLookup != null) {
					lc = fastLookup.ephemera$getLoadedChunk(cx, cz);
				} else {
					ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
					lc = chunk instanceof LevelChunk full ? full : null;
				}
				if (lc == null) {
					return false;
				}
				LevelChunkSection[] sections = lc.getSections();
				int lx0 = (cx == cx0) ? (x0 & 15) : 0;
				int lx1 = (cx == cx1) ? (x1 & 15) : 15;
				int bxBase = cx << 4;

				for (int sy = sy0; sy <= sy1; sy++) {
					if (sy < 0 || sy >= sections.length) {
						return false;
					}
					LevelChunkSection sec = sections[sy];
					if (sec == null || sec.hasOnlyAir()) {
						continue;
					}
					int secWorldY = (sy + minSecY) << 4;
					int ly0 = (sy == sy0) ? (scanY0 & 15) : 0;
					int ly1 = (sy == sy1) ? (y1 & 15) : 15;
					for (int ly = ly0; ly <= ly1; ly++) {
						int wy = secWorldY + ly;
						boolean isBelowGuard = wy < y0;
						int yOff = (wy - y0) * nz;
						for (int lz = lz0; lz <= lz1; lz++) {
							int wz = bzBase + lz;
							int zOff = (yOff + (wz - z0)) * nx;
							for (int lx = lx0; lx <= lx1; lx++) {
								BlockState state = sec.getBlockState(lx, ly, lz);
								if (state.isAir()) {
									continue;
								}
								if (isBelowGuard) {
									// Fences/walls/fence gates protrude upward into y0; let vanilla handle them.
									if (state.hasLargeCollisionShape()) {
										return false;
									}
									continue;
								}
								Block block = state.getBlock();
								if (block == Blocks.WATER || (lavaIsEmpty && block == Blocks.LAVA)) {
									continue;
								}
								if (state.hasLargeCollisionShape()) {
									return false;
								}
								int wx = bxBase + lx;
								mpos.set(wx, wy, wz);
								if (state.isCollisionShapeFullBlock(level, mpos)) {
									solidMask |= 1L << (zOff + (wx - x0));
								} else if (state.getCollisionShape(level, mpos).isEmpty()) {
									continue;
								} else {
									return false;
								}
							}
						}
					}
				}
			}
		}

		if (solidMask == 0L) {
			out[0] = dx;
			out[1] = dy;
			out[2] = dz;
			return true;
		}

		double curMinX = box.minX;
		double curMaxX = box.maxX;
		double curMinY = box.minY;
		double curMaxY = box.maxY;
		double curMinZ = box.minZ;
		double curMaxZ = box.maxZ;

		// Vanilla Direction.axisStepOrder(Vec3): Y first, then Z before X iff |dx| < |dz|.
		double resY = dy;
		if (resY != 0.0) {
			resY = collideAxisY(solidMask, x0, y0, z0, nx, nz, curMinX, curMaxX, curMinY, curMaxY, curMinZ, curMaxZ, resY);
			curMinY += resY;
			curMaxY += resY;
		}

		double resX = dx;
		double resZ = dz;
		if (Math.abs(dx) < Math.abs(dz)) {
			if (resZ != 0.0) {
				resZ = collideAxisZ(solidMask, x0, y0, z0, nx, nz, curMinX, curMaxX, curMinY, curMaxY, curMinZ, curMaxZ, resZ);
				curMinZ += resZ;
				curMaxZ += resZ;
			}
			if (resX != 0.0) {
				resX = collideAxisX(solidMask, x0, y0, z0, nx, nz, curMinX, curMaxX, curMinY, curMaxY, curMinZ, curMaxZ, resX);
			}
		} else {
			if (resX != 0.0) {
				resX = collideAxisX(solidMask, x0, y0, z0, nx, nz, curMinX, curMaxX, curMinY, curMaxY, curMinZ, curMaxZ, resX);
				curMinX += resX;
				curMaxX += resX;
			}
			if (resZ != 0.0) {
				resZ = collideAxisZ(solidMask, x0, y0, z0, nx, nz, curMinX, curMaxX, curMinY, curMaxY, curMinZ, curMaxZ, resZ);
			}
		}

		out[0] = resX;
		out[1] = resY;
		out[2] = resZ;
		return true;
	}

	private static double collideAxisY(
		long mask, int x0, int y0, int z0, int nx, int nz,
		double minX, double maxX, double minY, double maxY, double minZ, double maxZ,
		double dy
	) {
		if (Math.abs(dy) < EPS) {
			return 0.0;
		}
		int strideY = nx * nz;
		long m = mask;
		while (m != 0L) {
			int bit = Long.numberOfTrailingZeros(m);
			m &= m - 1L;
			int iy = bit / strideY;
			int rem = bit - iy * strideY;
			int iz = rem / nx;
			int ix = rem - iz * nx;
			int bx = x0 + ix;
			int bz = z0 + iz;
			if (maxX - EPS <= bx || minX + EPS >= bx + 1 || maxZ - EPS <= bz || minZ + EPS >= bz + 1) {
				continue;
			}
			int by = y0 + iy;
			if (dy > 0.0) {
				if (maxY - EPS < by) {
					double d = by - maxY;
					if (d >= -EPS && d < dy) {
						dy = d;
						if (Math.abs(dy) < EPS) {
							return 0.0;
						}
					}
				}
			} else {
				int top = by + 1;
				if (minY + EPS > top) {
					double d = top - minY;
					if (d <= EPS && d > dy) {
						dy = d;
						if (Math.abs(dy) < EPS) {
							return 0.0;
						}
					}
				}
			}
		}
		return dy;
	}

	private static double collideAxisX(
		long mask, int x0, int y0, int z0, int nx, int nz,
		double minX, double maxX, double minY, double maxY, double minZ, double maxZ,
		double dx
	) {
		if (Math.abs(dx) < EPS) {
			return 0.0;
		}
		int strideY = nx * nz;
		long m = mask;
		while (m != 0L) {
			int bit = Long.numberOfTrailingZeros(m);
			m &= m - 1L;
			int iy = bit / strideY;
			int rem = bit - iy * strideY;
			int iz = rem / nx;
			int ix = rem - iz * nx;
			int by = y0 + iy;
			int bz = z0 + iz;
			if (maxY - EPS <= by || minY + EPS >= by + 1 || maxZ - EPS <= bz || minZ + EPS >= bz + 1) {
				continue;
			}
			int bx = x0 + ix;
			if (dx > 0.0) {
				if (maxX - EPS < bx) {
					double d = bx - maxX;
					if (d >= -EPS && d < dx) {
						dx = d;
						if (Math.abs(dx) < EPS) {
							return 0.0;
						}
					}
				}
			} else {
				int right = bx + 1;
				if (minX + EPS > right) {
					double d = right - minX;
					if (d <= EPS && d > dx) {
						dx = d;
						if (Math.abs(dx) < EPS) {
							return 0.0;
						}
					}
				}
			}
		}
		return dx;
	}

	private static double collideAxisZ(
		long mask, int x0, int y0, int z0, int nx, int nz,
		double minX, double maxX, double minY, double maxY, double minZ, double maxZ,
		double dz
	) {
		if (Math.abs(dz) < EPS) {
			return 0.0;
		}
		int strideY = nx * nz;
		long m = mask;
		while (m != 0L) {
			int bit = Long.numberOfTrailingZeros(m);
			m &= m - 1L;
			int iy = bit / strideY;
			int rem = bit - iy * strideY;
			int iz = rem / nx;
			int ix = rem - iz * nx;
			int bx = x0 + ix;
			int by = y0 + iy;
			if (maxX - EPS <= bx || minX + EPS >= bx + 1 || maxY - EPS <= by || minY + EPS >= by + 1) {
				continue;
			}
			int bz = z0 + iz;
			if (dz > 0.0) {
				if (maxZ - EPS < bz) {
					double d = bz - maxZ;
					if (d >= -EPS && d < dz) {
						dz = d;
						if (Math.abs(dz) < EPS) {
							return 0.0;
						}
					}
				}
			} else {
				int south = bz + 1;
				if (minZ + EPS > south) {
					double d = south - minZ;
					if (d <= EPS && d > dz) {
						dz = d;
						if (Math.abs(dz) < EPS) {
							return 0.0;
						}
					}
				}
			}
		}
		return dz;
	}
}
