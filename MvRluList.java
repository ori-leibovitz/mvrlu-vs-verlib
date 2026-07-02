/**
 * MV-RLU-simplified sorted linked list — Day 2 morning.
 *
 * Skeleton only. TODOs map to the design-decisions document:
 *   M1: global AtomicLong clock (writers getAndIncrement at commit,
 *       readers get at read_lock)
 *   M2: cooperative GC — per-thread published activeTs; GC scans
 *       minActiveTs when the per-thread log crosses the capacity
 *       watermark (M3: capacity watermark only)
 *   M4: write latest committed copy back to master during GC, prune chain
 *   M5: reclamation = unlinking; JVM collects
 *   M6: fixed-size circular per-thread log; block if full after GC
 *   M7: hand-over-hand try_lock(pred as const, curr) for serializable
 *       insert/remove, per the ASPLOS'19 paper's own linked-list recipe
 *
 * Core objects: master node + per-node version chain of copies
 * (newest first), commit-ts on each committed copy, p-pending for the
 * uncommitted one. Reader picks first version with commit-ts <= its
 * local-ts, else the master.
 */
public final class MvRluList implements VersionedSet, Validatable {

    @Override public boolean insert(int key) { throw todo(); }
    @Override public boolean remove(int key) { throw todo(); }
    @Override public boolean contains(int key) { throw todo(); }
    @Override public long sumRange(int lo, int hi) { throw todo(); }
    @Override public int sizeSnapshot() { throw todo(); }
    @Override public void validateStructure() { throw todo(); }

    private static UnsupportedOperationException todo() {
        return new UnsupportedOperationException("MvRluList: Day 2 morning — see design-decisions.md M1-M7");
    }
}