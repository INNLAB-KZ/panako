package be.panako.strategy.olaf.storage;

/** Primitive near-hash to original-hash map; last insertion wins, as in HashMap. */
final class OlafQueryHashIndex {
    private long[] keys;
    private long[] values;
    private int size;
    private boolean hasZero;
    private long zeroValue;

    OlafQueryHashIndex() {
        keys = new long[16];
        values = new long[16];
    }

    void put(long key, long value) {
        if (key == 0) {
            hasZero = true;
            zeroValue = value;
            return;
        }
        int slot = slot(key);
        if (keys[slot] != 0) {
            values[slot] = value;
            return;
        }
        if (size + 1 > keys.length * 0.7) {
            grow();
            slot = slot(key);
        }
        keys[slot] = key;
        values[slot] = value;
        size++;
    }

    private int slot(long key) {
        long hash = key;
        hash = (hash ^ (hash >>> 33)) * 0xff51afd7ed558ccdL;
        hash = (hash ^ (hash >>> 33)) * 0xc4ceb9fe1a85ec53L;
        int slot = (int) (hash ^ (hash >>> 33)) & (keys.length - 1);
        while (keys[slot] != 0 && keys[slot] != key) slot = (slot + 1) & (keys.length - 1);
        return slot;
    }

    int find(long key) {
        if (key == 0) return hasZero ? keys.length : -1;
        int slot = slot(key);
        return keys[slot] == 0 ? -1 : slot;
    }

    long originalAt(int slot) { return slot == keys.length ? zeroValue : values[slot]; }
    long keyAt(int slot) { return slot == keys.length ? 0 : keys[slot]; }
    boolean occupied(int slot) { return slot == keys.length ? hasZero : keys[slot] != 0; }
    int slots() { return keys.length + 1; }

    long[] sortedKeys() {
        long[] result = new long[size + (hasZero ? 1 : 0)];
        int next = 0;
        if (hasZero) result[next++] = 0;
        for (long key : keys) if (key != 0) result[next++] = key;
        java.util.Arrays.sort(result);
        return result;
    }

    private void grow() {
        if (keys.length >= (1 << 29)) throw new IllegalStateException("OLAF query hash index too large");
        long[] oldKeys = keys;
        long[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new long[keys.length];
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != 0) {
                int target = slot(oldKeys[i]);
                keys[target] = oldKeys[i];
                values[target] = oldValues[i];
            }
        }
    }
}
