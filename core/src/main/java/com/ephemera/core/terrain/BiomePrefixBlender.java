package com.ephemera.core.terrain;

/**
 * Constant-time-per-block biome colour blending using separable integer running sums.
 *
 * <p>Input is a row-major {@code width x height} grid of packed {@code 0xRRGGBB} colours
 * that already includes a {@code radius}-wide apron on every side. The output holds the
 * box-filtered colour of each interior cell, {@code (width - 2r) x (height - 2r)} values.
 * Each output costs O(1) regardless of radius: a horizontal pass slides a window along
 * every row, then a vertical pass slides a window along every column of the partial sums.
 *
 * <p>The result is bit-identical to a naive per-cell average that sums each channel over
 * the {@code (2r+1)^2} window and divides with integer truncation.
 *
 * <p>Instances keep their scratch buffers and are not thread-safe; use one per thread.
 */
public final class BiomePrefixBlender {
	private int[] rowR = new int[0];
	private int[] rowG = new int[0];
	private int[] rowB = new int[0];

	/**
	 * Blends {@code colors} into {@code out}.
	 *
	 * @param colors packed RGB input, length at least {@code width * height}
	 * @param width  grid width including the apron
	 * @param height grid height including the apron
	 * @param radius blend radius, {@code >= 0}
	 * @param out    destination, length at least {@code (width - 2r) * (height - 2r)}
	 */
	public void blend(int[] colors, int width, int height, int radius, int[] out) {
		if (radius < 0) {
			throw new IllegalArgumentException("radius must be >= 0");
		}
		int innerW = width - 2 * radius;
		int innerH = height - 2 * radius;
		if (innerW <= 0 || innerH <= 0) {
			throw new IllegalArgumentException("grid too small for radius " + radius);
		}
		if (colors.length < width * height || out.length < innerW * innerH) {
			throw new IllegalArgumentException("buffer too small");
		}
		if (radius == 0) {
			for (int z = 0; z < innerH; z++) {
				System.arraycopy(colors, z * width, out, z * innerW, innerW);
			}
			return;
		}

		int window = 2 * radius + 1;
		int needed = innerW * height;
		ensureCapacity(needed);
		int[] sr = rowR;
		int[] sg = rowG;
		int[] sb = rowB;

		// Horizontal pass: window sums for every interior column of every row.
		for (int z = 0; z < height; z++) {
			int base = z * width;
			int r = 0;
			int g = 0;
			int b = 0;
			for (int x = 0; x < window; x++) {
				int c = colors[base + x];
				r += (c >>> 16) & 0xFF;
				g += (c >>> 8) & 0xFF;
				b += c & 0xFF;
			}
			int outBase = z * innerW;
			sr[outBase] = r;
			sg[outBase] = g;
			sb[outBase] = b;
			for (int x = 1; x < innerW; x++) {
				int add = colors[base + x + window - 1];
				int sub = colors[base + x - 1];
				r += ((add >>> 16) & 0xFF) - ((sub >>> 16) & 0xFF);
				g += ((add >>> 8) & 0xFF) - ((sub >>> 8) & 0xFF);
				b += (add & 0xFF) - (sub & 0xFF);
				sr[outBase + x] = r;
				sg[outBase + x] = g;
				sb[outBase + x] = b;
			}
		}

		// Vertical pass over the row sums.
		int count = window * window;
		for (int x = 0; x < innerW; x++) {
			int r = 0;
			int g = 0;
			int b = 0;
			for (int z = 0; z < window; z++) {
				int i = z * innerW + x;
				r += sr[i];
				g += sg[i];
				b += sb[i];
			}
			out[x] = pack(r / count, g / count, b / count);
			for (int z = 1; z < innerH; z++) {
				int add = (z + window - 1) * innerW + x;
				int sub = (z - 1) * innerW + x;
				r += sr[add] - sr[sub];
				g += sg[add] - sg[sub];
				b += sb[add] - sb[sub];
				out[z * innerW + x] = pack(r / count, g / count, b / count);
			}
		}
	}

	/**
	 * Blends {@code colors} into {@code out} with opaque alpha ({@code 0xFFRRGGBB}),
	 * matching {@code net.minecraft.util.ARGB.color(r, g, b)} bit-for-bit.
	 */
	public void blendOpaqueArgb(int[] colors, int width, int height, int radius, int[] out) {
		blend(colors, width, height, radius, out);
		int total = (width - 2 * radius) * (height - 2 * radius);
		for (int i = 0; i < total; i++) {
			out[i] |= 0xFF000000;
		}
	}

	/** Reference O(r^2)-per-cell implementation, used for verification. */
	public static int blendNaive(int[] colors, int width, int innerX, int innerZ, int radius) {
		int r = 0;
		int g = 0;
		int b = 0;
		for (int dz = 0; dz <= 2 * radius; dz++) {
			for (int dx = 0; dx <= 2 * radius; dx++) {
				int c = colors[(innerZ + dz) * width + innerX + dx];
				r += (c >>> 16) & 0xFF;
				g += (c >>> 8) & 0xFF;
				b += c & 0xFF;
			}
		}
		int count = (2 * radius + 1) * (2 * radius + 1);
		return pack(r / count, g / count, b / count);
	}

	private static int pack(int r, int g, int b) {
		return (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
	}

	private void ensureCapacity(int n) {
		if (rowR.length < n) {
			rowR = new int[n];
			rowG = new int[n];
			rowB = new int[n];
		}
	}
}
