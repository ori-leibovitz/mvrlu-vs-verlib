/**
 * The common interface for all set implementations in this project:
 * the coarse-lock baseline, the MV-RLU-simplified list, and the
 * Verlib/vCAS-simplified list.
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