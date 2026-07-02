/**
 * The common interface for all set implementations in this project:
 * the coarse-lock baseline, the MV-RLU-simplified list, and the
 * Verlib/vCAS-simplified list.
 *
 * <h2>Locked-in semantic decisions (Day 1) — do not change after Day 1</h2>
 * <ul>
 *   <li><b>Key domain:</b> keys are {@code int} in the open range
 *       {@code (Integer.MIN_VALUE, Integer.MAX_VALUE)}. The two extreme
 *       values are RESERVED for head/tail sentinels and must never be
 *       inserted.</li>
 *   <li><b>{@link #sumRange(int, int)} is INCLUSIVE on both ends:</b>
 *       it returns the sum of all keys k with {@code lo <= k <= hi}.</li>
 *   <li><b>Atomicity contract:</b> {@code insert}, {@code remove} and
 *       {@code contains} are linearizable single-key operations.
 *       {@code sumRange} and {@code sizeSnapshot} must be ATOMIC
 *       multi-node queries: each must reflect the state of the set at
 *       one single point within the call's interval (this is the whole
 *       point of the versioning mechanisms; the baseline achieves it
 *       trivially by holding the lock).</li>
 *   <li><b>Overflow:</b> {@code sumRange} returns {@code long}; callers
 *       keep key ranges small enough that a long sum cannot overflow.</li>
 * </ul>
 */
public interface VersionedSet {

    /** @return true iff key was absent and is now present. */
    boolean insert(int key);

    /** @return true iff key was present and is now absent. */
    boolean remove(int key);

    /** Fast-path current read. @return true iff key is present. */
    boolean contains(int key);

    /**
     * Atomic snapshot query: sum of all keys k with lo <= k <= hi,
     * as of a single point in time during this call.
     */
    long sumRange(int lo, int hi);

    /**
     * Atomic snapshot query: number of keys in the set, as of a single
     * point in time during this call.
     */
    int sizeSnapshot();
}