package be.panako.strategy.olaf.storage;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.RandomAccess;

/** Lossless hit storage: 16 payload bytes per hit, without a retained object per row. */
final class OlafPackedHitList extends AbstractList<OlafHit> implements RandomAccess {
    private static final int FIRST = 8;
    private static final int MAX_BLOCK = 4096;
    private static final int PREFIX = MAX_BLOCK - FIRST;
    private final long originalHash;
    private final List<long[]> blocks = new ArrayList<>(1);
    private BitSet removed;
    private int count;

    OlafPackedHitList(long originalHash) { this.originalHash = originalHash; }

    @Override public int size() { return count; }

    private static int block(int index) {
        return index < PREFIX ? 31 - Integer.numberOfLeadingZeros(index / FIRST + 1)
                : 9 + (index - PREFIX) / MAX_BLOCK;
    }

    private static int offset(int index, int block) {
        return index - (block < 9 ? FIRST * ((1 << block) - 1)
                : PREFIX + (block - 9) * MAX_BLOCK);
    }

    void append(long nearHash, int time, int resource) {
        int block = block(count);
        if (block == blocks.size()) blocks.add(new long[2 * (block < 9 ? FIRST << block : MAX_BLOCK)]);
        long[] data = blocks.get(block);
        int offset = 2 * offset(count, block);
        data[offset] = nearHash;
        data[offset + 1] = ((long) resource << 32) | (time & 0xffffffffL);
        count++;
        modCount++;
    }

    @Override public boolean add(OlafHit hit) {
        if (hit == null || hit.originalHash != originalHash) throw new IllegalArgumentException("Wrong hit bucket");
        append(hit.matchedNearHash, hit.t, hit.resourceID);
        return true;
    }

    @Override public OlafHit get(int index) {
        check(index);
        if (removed != null && removed.get(index)) return null;
        int block = block(index), offset = 2 * offset(index, block);
        long[] data = blocks.get(block);
        long packed = data[offset + 1];
        return new OlafHit(originalHash, data[offset], (int) packed, (int) (packed >>> 32));
    }

    @Override public OlafHit set(int index, OlafHit hit) {
        OlafHit previous = get(index);
        if (hit == null) {
            if (removed == null) removed = new BitSet();
            removed.set(index);
        } else {
            if (hit.originalHash != originalHash) throw new IllegalArgumentException("Wrong hit bucket");
            int block = block(index), offset = 2 * offset(index, block);
            long[] data = blocks.get(block);
            data[offset] = hit.matchedNearHash;
            data[offset + 1] = ((long) hit.resourceID << 32) | (hit.t & 0xffffffffL);
            if (removed != null) removed.clear(index);
        }
        return previous;
    }

    private void check(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException("Hit index " + index);
    }
}
