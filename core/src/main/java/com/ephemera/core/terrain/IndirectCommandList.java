package com.ephemera.core.terrain;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Growable list of indexed indirect draw commands in the layout shared by
 * {@code glMultiDrawElementsIndirect} ({@code DrawElementsIndirectCommand}) and
 * {@code vkCmdDrawIndexedIndirect} ({@code VkDrawIndexedIndirectCommand}): five 32-bit
 * fields {@code indexCount, instanceCount, firstIndex, baseVertex, firstInstance}, 20 bytes
 * per command, tightly packed.
 */
public final class IndirectCommandList {
	public static final int INTS_PER_COMMAND = 5;
	public static final int BYTES_PER_COMMAND = INTS_PER_COMMAND * Integer.BYTES;

	private static final VarHandle NATIVE_INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());

	private int[] data;
	private int size;

	public IndirectCommandList() {
		this(256);
	}

	public IndirectCommandList(int initialCommands) {
		data = new int[Math.max(1, initialCommands) * INTS_PER_COMMAND];
	}

	public void clear() {
		size = 0;
	}

	/** Number of commands. */
	public int size() {
		return size;
	}

	public void add(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
		int p = size * INTS_PER_COMMAND;
		if (p + INTS_PER_COMMAND > data.length) {
			data = Arrays.copyOf(data, Math.max(p + INTS_PER_COMMAND, data.length * 2));
		}
		data[p] = indexCount;
		data[p + 1] = instanceCount;
		data[p + 2] = firstIndex;
		data[p + 3] = baseVertex;
		data[p + 4] = firstInstance;
		size++;
	}

	/**
	 * Appends one command per sequential-index quad span: {@code indexCount = quads * 6},
	 * {@code firstIndex = 0}, {@code baseVertex = sectionBaseVertex + startQuad * 4}.
	 *
	 * @param spans {@code [startQuad, endQuad)} pairs as written by {@link QuadBuckets#spans}
	 * @return the largest index count appended, or 0
	 */
	public int addQuadSpans(int sectionBaseVertex, int[] spans, int spanCount) {
		return addQuadSpans(sectionBaseVertex, spans, 0, spanCount);
	}

	/**
	 * Appends one command per sequential-index quad span starting at {@code spanOffset} in {@code spans}.
	 */
	public int addQuadSpans(int sectionBaseVertex, int[] spans, int spanOffset, int spanCount) {
		return addQuadSpans(sectionBaseVertex, spans, spanOffset, spanCount, 0);
	}

	/**
	 * Appends one command per sequential-index quad span starting at {@code spanOffset} in
	 * {@code spans}, each with the given {@code firstInstance}.
	 */
	public int addQuadSpans(int sectionBaseVertex, int[] spans, int spanOffset, int spanCount, int firstInstance) {
		if (spanCount <= 0) {
			return 0;
		}
		int p = size * INTS_PER_COMMAND;
		int needed = p + spanCount * INTS_PER_COMMAND;
		int[] d = data;
		if (needed > d.length) {
			data = d = Arrays.copyOf(d, Math.max(needed, d.length * 2));
		}
		int largest = 0;
		int sp = spanOffset;
		for (int i = 0; i < spanCount; i++, sp += 2, p += INTS_PER_COMMAND) {
			int start = spans[sp];
			int indexCount = (spans[sp + 1] - start) * 6;
			d[p] = indexCount;
			d[p + 1] = 1;
			d[p + 2] = 0;
			d[p + 3] = sectionBaseVertex + (start << 2);
			d[p + 4] = firstInstance;
			if (indexCount > largest) {
				largest = indexCount;
			}
		}
		size += spanCount;
		return largest;
	}

	/** Appends every command of {@code other}. */
	public void addAll(IndirectCommandList other) {
		int n = other.size * INTS_PER_COMMAND;
		int p = size * INTS_PER_COMMAND;
		if (p + n > data.length) {
			data = Arrays.copyOf(data, Math.max(p + n, data.length * 2));
		}
		System.arraycopy(other.data, 0, data, p, n);
		size += other.size;
	}

	public int indexCount(int command) {
		return data[command * INTS_PER_COMMAND];
	}

	public int instanceCount(int command) {
		return data[command * INTS_PER_COMMAND + 1];
	}

	public int firstIndex(int command) {
		return data[command * INTS_PER_COMMAND + 2];
	}

	public int baseVertex(int command) {
		return data[command * INTS_PER_COMMAND + 3];
	}

	public int firstInstance(int command) {
		return data[command * INTS_PER_COMMAND + 4];
	}

	/**
	 * Writes all commands to {@code out} at its position in native byte order and advances the
	 * position. {@code out} must have {@code size() * BYTES_PER_COMMAND} bytes remaining.
	 */
	public void writeTo(ByteBuffer out) {
		int bytes = size * BYTES_PER_COMMAND;
		if (out.remaining() < bytes) {
			throw new IllegalArgumentException("need " + bytes + " bytes, have " + out.remaining());
		}
		writeTo(out, out.position());
		out.position(out.position() + bytes);
	}

	/**
	 * Writes all commands to {@code out} starting at absolute byte {@code offset}, in native
	 * byte order regardless of the buffer's order, without touching its position or creating
	 * view buffers.
	 *
	 * @return the byte offset just past the written commands
	 */
	public int writeTo(ByteBuffer out, int offset) {
		int ints = size * INTS_PER_COMMAND;
		if (offset < 0 || out.limit() - offset < ints * Integer.BYTES) {
			throw new IllegalArgumentException("need " + ints * Integer.BYTES + " bytes at " + offset + ", limit " + out.limit());
		}
		int[] d = data;
		int p = offset;
		for (int i = 0; i < ints; i++, p += Integer.BYTES) {
			NATIVE_INT.set(out, p, d[i]);
		}
		return p;
	}
}
