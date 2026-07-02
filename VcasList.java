/**
 * Verlib/vCAS-simplified sorted linked list — Day 2 afternoon.
 *
 * Skeleton only. TODOs map to the design-decisions document:
 *   V1: implement Algorithm 1 of Wei et al., PPoPP'21 verbatim:
 *       Camera { AtomicLong timestamp; takeSnapshot = read + one CAS
 *       increment (failed CAS is fine) }, VNode { val, ts (TBD), nextv },
 *       VersionedCAS { VHead }, and the initTS HELPING routine —
 *       every reader/failed-CASer that sees ts==TBD at the head helps
 *       set it before proceeding. That helping is the subtle core:
 *       be able to explain it at the whiteboard.
 *   V2: (optional, only if Day 2 ends early) recorded-once optimization
 *       for the list: embed ts+nextv in list nodes, drop the indirection.
 *   V3: lazy version-list pruning by min active snapshot handle
 *       (same publish mechanism as MvRluList's M2 — deliberate symmetry).
 *   V4: one global Camera; only next-pointers are versioned; immutable
 *       key fields stay plain.
 *
 * The list itself: Harris-style marked next pointers OR lazy-list with
 * per-node locks — DECIDE at the start of Day 2 afternoon and write the
 * choice into the design doc. (PPoPP'21 versions Harris's list directly;
 * a lock-based lazy list with versioned next-pointers is also legitimate
 * under verlib's "works with lock and CAS based algorithms".)
 */
public final class VcasList implements VersionedSet, Validatable {

    @Override public boolean insert(int key) { throw todo(); }
    @Override public boolean remove(int key) { throw todo(); }
    @Override public boolean contains(int key) { throw todo(); }
    @Override public long sumRange(int lo, int hi) { throw todo(); }
    @Override public int sizeSnapshot() { throw todo(); }
    @Override public void validateStructure() { throw todo(); }

    private static UnsupportedOperationException todo() {
        return new UnsupportedOperationException("VcasList: Day 2 afternoon — see design-decisions.md V1-V4");
    }
}